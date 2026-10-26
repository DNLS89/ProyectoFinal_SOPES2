package com.sopes.api.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ResourceConflictService {
    private static final String RESOURCE_A = "FORKLIFT";
    private static final String RESOURCE_B = "WAREHOUSE_WORKER";
    private static final long TIMEOUT_SECONDS = 120;

    private final Semaphore forklift = new Semaphore(1, true);
    private final Semaphore warehouseWorker = new Semaphore(1, true);
    private final SharedResourceService resources;
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redxela-conflict-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private ConflictSnapshot current = ConflictSnapshot.idle();

    public ResourceConflictService(SharedResourceService resources) {
        this.resources = resources;
    }

    public synchronized ConflictSnapshot trigger() {
        if (current.active()) return current.withMessage("El conflicto actual sigue activo; resuélvalo o cancélelo primero.");
        if (!forklift.tryAcquire()) return failed("No fue posible preparar el recurso de montacargas.");
        if (!warehouseWorker.tryAcquire()) {
            forklift.release();
            return failed("No fue posible preparar el recurso de personal de bodega.");
        }

        String id = UUID.randomUUID().toString();
        String forkliftName = resources.getName(RESOURCE_A);
        String workerName = resources.getName(RESOURCE_B);
        ConflictOperation first = new ConflictOperation("A", "Operación A: traslado voluminoso",
                forkliftName, workerName, "Bloqueada: espera el recurso solicitado");
        ConflictOperation second = new ConflictOperation("B", "Operación B: preparación paletizada",
                workerName, forkliftName, "Bloqueada: espera el recurso solicitado");
        String explanation = "A conserva " + forkliftName + " y solicita " + workerName
                + "; B conserva " + workerName + " y solicita " + forkliftName
                + ". Cada operación espera un recurso retenido por la otra.";
        current = new ConflictSnapshot(id, true, Instant.now(), Instant.now().plusSeconds(TIMEOUT_SECONDS),
                first, second, explanation, "Conflicto reproducido. Ningún hilo quedó bloqueado.", "");

        // Los semáforos aislados representan recursos de capacidad uno sin detener pedidos reales.
        if (warehouseWorker.tryAcquire()) warehouseWorker.release();
        if (forklift.tryAcquire()) forklift.release();
        try {
            expiry.schedule(() -> expire(id), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            releaseBoth();
            return failed("El temporizador de seguridad no está disponible; se liberaron los recursos.");
        }
        return current;
    }

    public synchronized ConflictSnapshot resolve(String winnerId) {
        if (!current.active()) return current.withMessage("No hay un conflicto activo para resolver.");
        boolean firstWins = "A".equals(winnerId);
        if (!firstWins && !"B".equals(winnerId)) return current.withMessage("Seleccione la operación A o B.");

        Semaphore winnerHeld = firstWins ? forklift : warehouseWorker;
        Semaphore loserHeld = firstWins ? warehouseWorker : forklift;
        ConflictOperation winner = firstWins ? current.operationA() : current.operationB();
        ConflictOperation loser = firstWins ? current.operationB() : current.operationA();
        loserHeld.release();
        if (!loserHeld.tryAcquire()) {
            return closeAsError("No se pudo transferir el recurso solicitado; ambos recursos fueron liberados.");
        }
        winnerHeld.release();
        loserHeld.release();
        loser.status = "Devuelta a la cola de espera";
        winner.status = "Continuó con ambos recursos; luego los liberó";
        current = current.resolved(winnerId,
                winner.name + " continuó; " + loser.name + " volvió a la cola de espera.");
        return current;
    }

    public synchronized ConflictSnapshot cancel() {
        if (current.active()) releaseBoth();
        current = current.cancelled();
        return current;
    }

    public synchronized ConflictSnapshot snapshot() { return current; }

    private synchronized void expire(String id) {
        if (current.active() && current.id().equals(id)) {
            releaseBoth();
            current = current.cancelledWithMessage("Conflicto cancelado automáticamente tras dos minutos.");
        }
    }

    private ConflictSnapshot failed(String message) {
        current = new ConflictSnapshot(UUID.randomUUID().toString(), false, Instant.now(), null,
                null, null, "", message, "");
        return current;
    }

    private ConflictSnapshot closeAsError(String message) {
        releaseBoth();
        current = current.cancelledWithMessage(message);
        return current;
    }

    private void releaseBoth() {
        if (forklift.availablePermits() == 0) forklift.release();
        if (warehouseWorker.availablePermits() == 0) warehouseWorker.release();
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (current.active()) releaseBoth();
        expiry.shutdownNow();
    }

    public record ConflictSnapshot(String id, boolean active, Instant startedAt, Instant expiresAt,
                                   ConflictOperation operationA, ConflictOperation operationB,
                                   String explanation, String message, String winnerId) {
        private static ConflictSnapshot idle() {
            return new ConflictSnapshot("", false, null, null, null, null, "",
                    "No hay un conflicto activo.", "");
        }

        private ConflictSnapshot withMessage(String nextMessage) {
            return new ConflictSnapshot(id, active, startedAt, expiresAt, operationA, operationB,
                    explanation, nextMessage, winnerId);
        }

        private ConflictSnapshot resolved(String winner, String nextMessage) {
            return new ConflictSnapshot(id, false, startedAt, null, operationA, operationB,
                    explanation, nextMessage, winner);
        }

        private ConflictSnapshot cancelled() {
            return cancelledWithMessage("Conflicto cancelado; recursos liberados.");
        }

        private ConflictSnapshot cancelledWithMessage(String nextMessage) {
            if (operationA != null) operationA.status = "Cancelada; recursos liberados";
            if (operationB != null) operationB.status = "Cancelada; recursos liberados";
            return new ConflictSnapshot(id, false, startedAt, null, operationA, operationB,
                    explanation, nextMessage, "");
        }
    }

    public static final class ConflictOperation {
        private final String id;
        private final String name;
        private final String holds;
        private final String requests;
        private volatile String status;

        private ConflictOperation(String id, String name, String holds, String requests, String status) {
            this.id = id;
            this.name = name;
            this.holds = holds;
            this.requests = requests;
            this.status = status;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String holds() { return holds; }
        public String requests() { return requests; }
        public String status() { return status; }
    }
}
