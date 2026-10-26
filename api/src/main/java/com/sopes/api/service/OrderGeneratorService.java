package com.sopes.api.service;

import com.sopes.api.model.OrderLine;
import com.sopes.api.model.ServiceLevel;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderGeneratorService {
    private final OrderIntakeService intake;
    private final InventoryService inventory;
    private final MerchandiseCatalog catalog;
    private volatile Thread generatorThread;
    private volatile int intervalSeconds = 5;

    public OrderGeneratorService(OrderIntakeService intake, InventoryService inventory, MerchandiseCatalog catalog) {
        this.intake = intake;
        this.inventory = inventory;
        this.catalog = catalog;
    }

    public synchronized void start(int intervalSeconds) {
        if (intervalSeconds < 1 || intervalSeconds > 3600) {
            throw new IllegalArgumentException("El intervalo debe estar entre 1 y 3600 segundos.");
        }
        this.intervalSeconds = intervalSeconds;
        if (isRunning()) return;
        generatorThread = new Thread(this::generateLoop, "redxela-order-generator");
        generatorThread.setDaemon(true);
        generatorThread.start();
    }

    public synchronized void stop() {
        Thread thread = generatorThread;
        generatorThread = null;
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        Thread thread = generatorThread;
        return thread != null && thread.isAlive();
    }

    public int getIntervalSeconds() { return intervalSeconds; }

    private void generateLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalSeconds * 1000L);
                generateOne();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // Un pedido inválido no debe terminar el generador.
            }
        }
    }

    private void generateOne() {
        var lots = inventory.findLots();
        OrderLine line;
        if (lots.isEmpty()) {
            line = new OrderLine("SIM-001", "Producto de simulación", catalog.findAll().get(0), 1);
        } else {
            var lot = lots.get(ThreadLocalRandom.current().nextInt(lots.size()));
            var type = catalog.findByCode(lot.merchandiseTypeCode());
            int available = inventory.getProductStock(lot.productCode(), type);
            line = new OrderLine(lot.productCode(), "Producto " + lot.productCode(), type, Math.max(1, Math.min(available, 1 + ThreadLocalRandom.current().nextInt(3))));
        }
        ServiceLevel[] levels = ServiceLevel.values();
        ServiceLevel level = levels[ThreadLocalRandom.current().nextInt(levels.length)];
        String customer = "Cliente simulado " + ThreadLocalRandom.current().nextInt(1, 10_000);
        intake.create(customer, "sim" + ThreadLocalRandom.current().nextInt(1, 1_000_000) + "@redxela.local",
                level, List.of(line.productCode()), List.of(line.productName()), List.of(line.merchandiseType().code()), List.of(line.quantity()));
    }

    @PreDestroy
    public void shutdown() { stop(); }
}
