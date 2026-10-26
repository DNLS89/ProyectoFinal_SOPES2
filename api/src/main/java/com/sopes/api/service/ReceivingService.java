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

@Service
public class ReceivingService {
    private static final int QUEUE_CAPACITY = 100;
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

    public ReceiptResult submit(ReceiptRequest request) {
        String id = "REC-" + UUID.randomUUID();
        QueuedReceipt receipt = new QueuedReceipt(id, request);
        try {
            // El tiempo acotado da oportunidad al consumidor y evita bloquear la respuesta web.
            if (!queue.offer(receipt, 250, TimeUnit.MILLISECONDS)) {
                return new ReceiptResult(id, ReceiptStatus.REJECTED, request.quantity(), 0,
                        "Cola llena; el recibo no fue aceptado. Intente nuevamente.", Instant.now(), java.util.Map.of());
            }
            return new ReceiptResult(id, ReceiptStatus.QUEUED, request.quantity(), 0,
                    "Recibo aceptado en la cola FIFO para procesamiento.", null, java.util.Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReceiptResult(id, ReceiptStatus.REJECTED, request.quantity(), 0,
                    "Solicitud interrumpida antes de ser aceptada.", Instant.now(), java.util.Map.of());
        }
    }

    private void consume() {
        while (running.get() || !queue.isEmpty()) {
            try {
                QueuedReceipt queued = queue.poll(200, TimeUnit.MILLISECONDS);
                if (queued == null) continue;
                inFlight.incrementAndGet();
                try {
                    ReceiptResult result;
                    try {
                        ReceiptResult stored = storeWithScanner(queued);
                        result = new ReceiptResult(queued.id(), stored.status(), stored.requestedUnits(),
                                stored.acceptedUnits(), stored.message(), stored.processedAt(), stored.allocations());
                    } catch (RuntimeException e) {
                        result = new ReceiptResult(queued.id(), ReceiptStatus.FAILED, queued.request().quantity(), 0,
                                "Error procesando el recibo: " + e.getMessage(), Instant.now(), java.util.Map.of());
                    }
                    recentResults.add(0, result);
                    if (recentResults.size() > 50) recentResults.remove(recentResults.size() - 1);
                    if (result.acceptedUnits() > 0) waitingOrders.reconsider(inventory);
                } finally {
                    inFlight.decrementAndGet();
                }
            } catch (InterruptedException e) {
                if (!running.get() && queue.isEmpty()) break;
            }
        }
    }

    private ReceiptResult storeWithScanner(QueuedReceipt queued) {
        while (true) {
            try (SharedResourceService.Lease ignored = resources.acquire(queued.id(), List.of("BARCODE_SCANNER"))) {
                return inventory.store(queued.request());
            } catch (InterruptedException e) {
                // El recibo ya salió de la cola; se reintenta para no perderlo durante el cierre.
            }
        }
    }

    public int getPendingCount() { return queue.size() + inFlight.get(); }
    public List<ReceiptResult> findRecentResults() { return List.copyOf(recentResults); }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumer == null) return;
        try {
            consumer.join(5_000);
            if (consumer.isAlive()) {
                consumer.interrupt();
                consumer.join(1_000);
            }
        } catch (InterruptedException e) {
            consumer.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    private record QueuedReceipt(String id, ReceiptRequest request) { }
}
