package com.sopes.api.service;

import com.sopes.api.model.ServiceLevel;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderGeneratorService {
    private final OrderIntakeService intake;
    private final InventoryService inventory;
    private final ProductCatalog products;
    private volatile Thread generatorThread;
    private volatile int intervalSeconds = 5;

    public OrderGeneratorService(OrderIntakeService intake, InventoryService inventory, ProductCatalog products) {
        this.intake = intake;
        this.inventory = inventory;
        this.products = products;
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
        var product = products.findByCode("SKU-001");
        if (lots.isEmpty()) {
            product = products.findByCode("SKU-001");
        } else {
            var lot = lots.stream().filter(item -> products.findAll().stream().anyMatch(p -> p.code().equals(item.productCode())))
                    .findAny().orElse(null);
            if (lot != null) product = products.findByCode(lot.productCode());
        }
        ServiceLevel[] levels = ServiceLevel.values();
        ServiceLevel level = levels[ThreadLocalRandom.current().nextInt(levels.length)];
        int customerNumber = ThreadLocalRandom.current().nextInt(1, 11);
        String customer = "Cliente simulado " + customerNumber;
        int available = inventory.getProductStock(product.code(), product.merchandiseType());
        int quantity = available > 0 ? ThreadLocalRandom.current().nextInt(1, Math.min(available, 3) + 1) : 1;
        intake.create(null, customer, "sim" + customerNumber + "@redxela.local", level,
                List.of(product.code()), List.of(quantity));
    }

    @PreDestroy
    public void shutdown() { stop(); }
}
