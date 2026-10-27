package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.OperationalError;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Coordina el despacho concurrente, el uso de recursos y el cierre de los trabajadores. */
@Service
public class OrderProcessingService {
    private static final int WORKER_COUNT = 4;
    private static final int MAX_PENDING_TASKS = 100;
    private final WaitingOrderService orders;
    private final InventoryService inventory;
    private final SharedResourceService resources;
    private final List<OperationalError> recentErrors = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_TASKS), runnable -> {
        Thread thread = new Thread(runnable, "redxela-order-worker");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running;
    private Thread dispatcher;

    public OrderProcessingService(WaitingOrderService orders, InventoryService inventory,
                                  SharedResourceService resources) {
        this.orders = orders;
        this.inventory = inventory;
        this.resources = resources;
    }

    @PostConstruct
    public void start() {
        running = true;
        dispatcher = new Thread(this::dispatch, "redxela-order-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    private void dispatch() {
        // Un despachador reclama pedidos y los reparte entre cuatro workers; el límite evita crecer sin control.
        while (running) {
            if (workers.getActiveCount() + workers.getQueue().size() >= WORKER_COUNT + MAX_PENDING_TASKS) {
                pauseBriefly();
                continue;
            }
            WaitingOrderService.ProcessingClaim claim = orders.claimNext(inventory);
            if (claim == null) {
                pauseBriefly();
                continue;
            }
            OrderTask task = new OrderTask(claim);
            try {
                workers.execute(task);
            } catch (RuntimeException e) {
                if (running) recordError("Despachador", claim.order().id(), e);
                task.restore();
                pauseBriefly();
            }
        }
    }

    private void pauseBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(WaitingOrderService.ProcessingClaim claim) {
        SharedResourceService.Lease lease = null;
        boolean settled = false;
        try {
            // El stock ya quedó reservado al reclamar; aquí se esperan los permisos antes de marcar el inicio real.
            lease = resources.acquire(claim.order());
            orders.markStarted(claim.order().id());
            // Medio segundo por unidad vincula la duración simulada con el tamaño del pedido.
            long totalMillis = Math.max(1, claim.order().getTotalUnits()) * 500L;
            long elapsed = 0;
            while (elapsed < totalMillis) {
                long step = Math.min(100, totalMillis - elapsed);
                Thread.sleep(step);
                elapsed += step;
                int progress = (int) Math.min(99, elapsed * 100 / totalMillis);
                long remaining = (totalMillis - elapsed + 999) / 1000;
                orders.updateProgress(claim.order().id(), progress, remaining);
            }
            orders.complete(claim, inventory);
            settled = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            recordError("Procesamiento", claim.order().id(), e);
            try {
                orders.block(claim, inventory);
                settled = true;
            } catch (RuntimeException recoveryError) {
                recordError("Recuperación", claim.order().id(), recoveryError);
            }
        } finally {
            // El lease devuelve recursos y una orden no asentada libera su stock y vuelve a quedar disponible.
            if (lease != null) lease.close();
            if (!settled) orders.returnToQueue(claim, inventory);
        }
    }

    public List<CustomerOrder> findActiveOrders() { return orders.findActiveOrders(); }
    public List<SharedResourceService.ResourceState> resourceSnapshot() { return resources.snapshot(); }
    public List<OperationalError> findRecentErrors() { return List.copyOf(recentErrors); }

    private void recordError(String source, String reference, RuntimeException error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        recentErrors.add(0, new OperationalError(source, reference, message, Instant.now()));
        while (recentErrors.size() > 20) recentErrors.remove(recentErrors.size() - 1);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        boolean interrupted = false;
        String failure = null;
        // Se detiene primero al productor de tareas para que ningún reclamo compita con el cierre del pool.
        if (dispatcher != null) {
            dispatcher.interrupt();
            try {
                dispatcher.join(2_000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (dispatcher.isAlive()) {
                failure = "El despachador de órdenes no terminó dentro del plazo.";
            }
        }
        // El cierre ordenado permite terminar trabajos activos; el cierre forzado recupera los que no iniciaron.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                // El cierre forzado interrumpe el trabajo activo y devuelve las tareas que nunca iniciaron.
                restorePending(workers.shutdownNow());
                if (!awaitAfterForce(interrupted)) {
                    failure = appendFailure(failure, "Los trabajadores de órdenes no terminaron tras el cierre forzado.");
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            restorePending(workers.shutdownNow());
            if (!awaitAfterForce(interrupted)) {
                failure = appendFailure(failure, "Los trabajadores de órdenes no terminaron tras la interrupción del cierre.");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw new IllegalStateException(failure);
    }

    private boolean awaitAfterForce(boolean interruptedBefore) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = interruptedBefore;
        while (!workers.isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                restorePending(workers.shutdownNow());
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return workers.isTerminated();
    }

    private void restorePending(List<Runnable> pending) {
        for (Runnable runnable : pending) {
            if (runnable instanceof OrderProcessingService.OrderTask task) task.restore();
        }
    }

    private String appendFailure(String current, String message) {
        return current == null ? message : current + " " + message;
    }

    private final class OrderTask implements Runnable {
        private final WaitingOrderService.ProcessingClaim claim;
        private boolean restored;

        private OrderTask(WaitingOrderService.ProcessingClaim claim) { this.claim = claim; }

        @Override
        public void run() { process(claim); }

        private synchronized void restore() {
            if (restored) return;
            // Dos rutas de cierre pueden recuperar esta tarea; el flag evita devolverla dos veces.
            restored = true;
            orders.returnToQueue(claim, inventory);
        }
    }
}
