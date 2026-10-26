package com.sopes.api.service;

import com.sopes.api.model.ServiceLevel;
import com.sopes.api.model.OperationalError;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderGeneratorService {
    private final OrderIntakeService intake;
    private final InventoryService inventory;
    private final ProductCatalog products;
    private final List<OperationalError> recentErrors = new CopyOnWriteArrayList<>();
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
        if (thread == null) return;
        thread.interrupt();
        boolean interrupted = false;
        // Se conserva la referencia hasta confirmar que el generador ya no puede crear órdenes.
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
                thread.interrupt();
            }
        }
        if (generatorThread == thread) generatorThread = null;
        if (interrupted) Thread.currentThread().interrupt();
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
            } catch (RuntimeException e) {
                recordError(e);
                return;
            }
        }
    }

    public List<OperationalError> findRecentErrors() { return List.copyOf(recentErrors); }

    private void recordError(RuntimeException error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        recentErrors.add(0, new OperationalError("Generador", "", message, Instant.now()));
        while (recentErrors.size() > 20) recentErrors.remove(recentErrors.size() - 1);
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
