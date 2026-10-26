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
            lease = resources.acquire(claim.order());
            orders.markStarted(claim.order().id());
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
        if (dispatcher != null) {
            dispatcher.interrupt();
            try {
                dispatcher.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> pending = workers.shutdownNow();
                for (Runnable runnable : pending) {
                    if (runnable instanceof OrderProcessingService.OrderTask task) task.restore();
                }
                workers.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            List<Runnable> pending = workers.shutdownNow();
            for (Runnable runnable : pending) {
                if (runnable instanceof OrderProcessingService.OrderTask task) task.restore();
            }
            Thread.currentThread().interrupt();
        }
    }

    private final class OrderTask implements Runnable {
        private final WaitingOrderService.ProcessingClaim claim;
        private boolean restored;

        private OrderTask(WaitingOrderService.ProcessingClaim claim) { this.claim = claim; }

        @Override
        public void run() { process(claim); }

        private synchronized void restore() {
            if (restored) return;
            restored = true;
            orders.returnToQueue(claim, inventory);
        }
    }
}
