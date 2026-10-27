package com.sopes.api.service;

import com.sopes.api.model.ReceiptRequest;
import com.sopes.api.model.ReceiptResult;
import com.sopes.api.model.ReceiptStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encola recepciones FIFO y las procesa en un consumidor separado del formulario web. */
@Service
public class ReceivingService {
    private static final int QUEUE_CAPACITY = 100;
    private static final int RESULT_HISTORY_LIMIT = 200;
    private final BlockingQueue<QueuedReceipt> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final List<ReceiptResult> recentResults = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final InventoryService inventory;
    private final WaitingOrderService waitingOrders;
    private final SharedResourceService resources;
    private Thread consumer;

    public ReceivingService(InventoryService inventory, WaitingOrderService waitingOrders, SharedResourceService resources) {
        this.inventory = inventory;
        this.waitingOrders = waitingOrders;
        this.resources = resources;
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        consumer = new Thread(this::consume, "redxela-inventory-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    public synchronized ReceiptResult submit(ReceiptRequest request) {
        String id = "REC-" + UUID.randomUUID();
        if (!running.get()) {
            ReceiptResult rejected = new ReceiptResult(id, ReceiptStatus.REJECTED, request.quantity(), 0,
                    "El servicio de recepción está detenido; el recibo no fue aceptado.", Instant.now(), java.util.Map.of());
            addResult(rejected);
            return rejected;
        }
        QueuedReceipt receipt = new QueuedReceipt(id, request);
        ReceiptResult queued = new ReceiptResult(id, ReceiptStatus.QUEUED, request.quantity(), 0,
                "Recibo aceptado en la cola FIFO para procesamiento.", null, java.util.Map.of());
        // El registro provisional existe antes de que el consumidor pueda publicar el resultado final.
        addResult(queued);
        try {
            // El tiempo acotado da oportunidad al consumidor y evita bloquear la respuesta web.
            if (!queue.offer(receipt, 250, TimeUnit.MILLISECONDS)) {
                ReceiptResult rejected = new ReceiptResult(id, ReceiptStatus.REJECTED, request.quantity(), 0,
                        "Cola llena; el recibo no fue aceptado. Intente nuevamente.", Instant.now(), java.util.Map.of());
                addResult(rejected);
                return rejected;
            }
            return queued;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ReceiptResult rejected = new ReceiptResult(id, ReceiptStatus.REJECTED, request.quantity(), 0,
                    "Solicitud interrumpida antes de ser aceptada.", Instant.now(), java.util.Map.of());
            addResult(rejected);
            return rejected;
        }
    }

    private void consume() {
        // Un único consumidor aplica las recepciones en orden de aceptación y separa el formulario del inventario.
        while (running.get() || !queue.isEmpty()) {
            try {
                QueuedReceipt queued = queue.poll(200, TimeUnit.MILLISECONDS);
                if (queued == null) continue;
                inFlight.incrementAndGet();
                try {
                    ReceiptResult result;
                    try {
                        if (!running.get()) throw new InterruptedException("Cierre del servicio");
                        ReceiptResult stored = storeWithScanner(queued);
                        result = new ReceiptResult(queued.id(), stored.status(), stored.requestedUnits(),
                                stored.acceptedUnits(), stored.message(), stored.processedAt(), stored.allocations());
                    } catch (InterruptedException e) {
                        result = failedReceipt(queued, "El recibo aceptado no se procesó por el cierre del servicio.");
                    } catch (RuntimeException e) {
                        result = failedReceipt(queued, "Error procesando el recibo: " + describe(e));
                    }
                    addResult(result);
                    if (result.acceptedUnits() > 0) waitingOrders.reconsider(inventory);
                } finally {
                    inFlight.decrementAndGet();
                }
            } catch (InterruptedException e) {
                if (!running.get() && queue.isEmpty()) break;
            }
        }
    }

    private ReceiptResult storeWithScanner(QueuedReceipt queued) throws InterruptedException {
        // Recepción y despacho electrónico comparten el lease; así el escáner único nunca se usa a la vez.
        try (SharedResourceService.Lease ignored = resources.acquire(queued.id(), List.of("BARCODE_SCANNER"))) {
            return inventory.store(queued.request());
        }
    }

    private ReceiptResult failedReceipt(QueuedReceipt queued, String message) {
        return new ReceiptResult(queued.id(), ReceiptStatus.FAILED, queued.request().quantity(), 0,
                message, Instant.now(), java.util.Map.of());
    }

    private void addResult(ReceiptResult result) {
        recentResults.removeIf(existing -> existing.receiptId().equals(result.receiptId()));
        recentResults.add(0, result);
        while (recentResults.size() > RESULT_HISTORY_LIMIT) recentResults.remove(recentResults.size() - 1);
    }

    private String describe(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    public int getPendingCount() { return queue.size() + inFlight.get(); }
    public List<ReceiptResult> findRecentResults() { return List.copyOf(recentResults); }

    @PreDestroy
    public synchronized void stop() {
        running.set(false);
        if (consumer == null) return;
        // La interrupción despierta el sondeo; el join espera que el consumidor cierre su trabajo y registre el recibo.
        consumer.interrupt();
        boolean interrupted = false;
        while (consumer.isAlive()) {
            try {
                consumer.join();
            } catch (InterruptedException e) {
                interrupted = true;
                consumer.interrupt();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record QueuedReceipt(String id, ReceiptRequest request) { }
}
