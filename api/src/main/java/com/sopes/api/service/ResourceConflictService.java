package com.sopes.api.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Simula un interbloqueo aislado y administra su resolución sin detener pedidos reales. */
@Service
public class ResourceConflictService {
    private static final String RESOURCE_A = "FORKLIFT";
    private static final String RESOURCE_B = "WAREHOUSE_WORKER";
    private static final long TIMEOUT_SECONDS = 120;
    private static final long OPERATION_MILLIS = 1_200;
    private static final long RETRY_MILLIS = 200;

    private final java.util.concurrent.Semaphore forklift = new java.util.concurrent.Semaphore(1, true);
    private final java.util.concurrent.Semaphore warehouseWorker = new java.util.concurrent.Semaphore(1, true);
    private final SharedResourceService resources;
    private final Queue<ConflictOperation> waiting = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "redxela-conflict-simulation");
        thread.setDaemon(true);
        return thread;
    });
    private boolean forkliftHeld;
    private boolean workerHeld;
    private ConflictSnapshot current = ConflictSnapshot.idle();

    public ResourceConflictService(SharedResourceService resources) {
        this.resources = resources;
    }

    public synchronized ConflictSnapshot trigger() {
        if (current.active()) return current.withMessage("El conflicto actual sigue activo; resuélvalo o cancélelo primero.");
        if (!forklift.tryAcquire()) return failed("No fue posible preparar el recurso de montacargas.");
        forkliftHeld = true;
        if (!warehouseWorker.tryAcquire()) {
            releaseForklift();
            return failed("No fue posible preparar el recurso de personal de bodega.");
        }
        workerHeld = true;

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
        // El ciclo se representa con permisos privados y estado visible; no bloquea workers ni recursos de producción.
        current = new ConflictSnapshot(id, true, false, Instant.now(), Instant.now().plusSeconds(TIMEOUT_SECONDS),
                first, second, explanation, "Conflicto reproducido con permisos aislados; no hay hilos bloqueados.", "");

        try {
            scheduler.schedule(() -> expire(id), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            releaseHeld();
            return failed("El temporizador de seguridad no está disponible; se liberaron los recursos.");
        }
        return current;
    }

    public synchronized ConflictSnapshot resolve(String winnerId) {
        if (!current.active()) return current.withMessage("No hay un conflicto activo para resolver.");
        if (current.resolving()) return current.withMessage("La simulación ya está ejecutando la resolución.");
        boolean firstWins = "A".equals(winnerId);
        if (!firstWins && !"B".equals(winnerId)) return current.withMessage("Seleccione la operación A o B.");

        ConflictOperation winner = firstWins ? current.operationA() : current.operationB();
        ConflictOperation loser = firstWins ? current.operationB() : current.operationA();
        if (!transferRequestedPermit(firstWins)) {
            releaseHeld();
            current = current.cancelledWithMessage("No se pudo transferir el permiso; se canceló el escenario y se liberaron los recursos.");
            return current;
        }

        winner.status = "Ejecutando operación simulada con ambos recursos";
        loser.status = "En cola de reintento; no retiene recursos";
        winner.holds = bothResourceNames();
        winner.requests = "Ninguno";
        loser.holds = "Ninguno";
        loser.requests = bothResourceNames() + " para el reintento";
        waiting.clear();
        waiting.add(loser);
        current = current.withResolution(winnerId,
                "La operación elegida ejecuta; la otra espera el reintento.");
        String id = current.id();
        try {
            scheduler.schedule(() -> finishWinner(id, winner), OPERATION_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            releaseHeld();
            waiting.clear();
            winner.status = "No ejecutada; escenario cancelado";
            loser.status = "No ejecutada; escenario cancelado";
            current = current.cancelledWithMessage("No se pudo programar la operación; se canceló el escenario.");
        }
        return current;
    }

    private boolean transferRequestedPermit(boolean firstWins) {
        // El ganador toma el permiso liberado por el perdedor antes de comenzar su trabajo simulado.
        if (firstWins) {
            releaseWorker();
            if (!warehouseWorker.tryAcquire()) return false;
            workerHeld = true;
        } else {
            releaseForklift();
            if (!forklift.tryAcquire()) return false;
            forkliftHeld = true;
        }
        return true;
    }

    private void finishWinner(String id, ConflictOperation winner) {
        synchronized (this) {
            if (!isCurrent(id)) return;
            releaseHeld();
            winner.status = "Operación simulada completada; recursos liberados";
            winner.holds = "Ninguno";
            ConflictOperation operation = waiting.peek();
            if (operation != null) {
                operation.holds = "Ninguno";
                operation.requests = bothResourceNames();
            }
            try {
                scheduler.schedule(() -> retryWaiting(id), RETRY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                finishWithoutRetry("La operación elegida terminó, pero no se pudo programar el reintento.");
            }
        }
    }

    private void retryWaiting(String id) {
        synchronized (this) {
            if (!isCurrent(id) || waiting.isEmpty()) return;
            // El reintento usa adquisiciones no bloqueantes; si falta un permiso, devuelve el parcial y vuelve a esperar.
            if (!forklift.tryAcquire()) {
                scheduleRetry(id);
                return;
            }
            forkliftHeld = true;
            if (!warehouseWorker.tryAcquire()) {
                releaseForklift();
                scheduleRetry(id);
                return;
            }
            workerHeld = true;
            ConflictOperation retry = waiting.peek();
            retry.status = "Reintentando operación con ambos recursos";
            retry.holds = bothResourceNames();
            retry.requests = "Ninguno";
            try {
                scheduler.schedule(() -> finishLoser(id, retry), OPERATION_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                releaseHeld();
                finishWithoutRetry("No se pudo programar el reintento; se liberaron los recursos.");
            }
        }
    }

    private void scheduleRetry(String id) {
        try {
            scheduler.schedule(() -> retryWaiting(id), RETRY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            finishWithoutRetry("No se pudo continuar el reintento de la operación en espera.");
        }
    }

    private synchronized void finishLoser(String id, ConflictOperation loser) {
        if (!isCurrent(id)) return;
        releaseHeld();
        waiting.remove(loser);
        loser.status = "Operación completada tras reintento; recursos liberados";
        loser.holds = "Ninguno";
        loser.requests = "Ninguno";
        ConflictOperation winner = loser == current.operationA() ? current.operationB() : current.operationA();
        winner.requests = "Ninguno";
        current = current.resolved("La operación ganadora completó su trabajo simulado; la otra volvió a la cola, reintentó y completó.");
    }

    private String bothResourceNames() {
        return resources.getName(RESOURCE_A) + " y " + resources.getName(RESOURCE_B);
    }

    private void finishWithoutRetry(String message) {
        releaseHeld();
        ConflictOperation loser = waiting.poll();
        if (loser != null) {
            loser.status = "Reintento cancelado; recursos liberados";
            loser.holds = "Ninguno";
            loser.requests = "Ninguno";
        }
        ConflictOperation winner = "A".equals(current.winnerId()) ? current.operationA() : current.operationB();
        if (winner != null) winner.requests = "Ninguno";
        current = current.resolved(message);
    }

    public synchronized ConflictSnapshot cancel() {
        if (current.active()) releaseHeld();
        waiting.clear();
        current = current.cancelled();
        return current;
    }

    public synchronized ConflictSnapshot snapshot() { return current; }

    private synchronized void expire(String id) {
        if (isCurrent(id)) {
            releaseHeld();
            waiting.clear();
            current = current.cancelledWithMessage("Conflicto cancelado automáticamente tras dos minutos.");
        }
    }

    private boolean isCurrent(String id) { return current.active() && current.id().equals(id); }

    private ConflictSnapshot failed(String message) {
        current = new ConflictSnapshot(UUID.randomUUID().toString(), false, false, Instant.now(), null,
                null, null, "", message, "");
        return current;
    }

    private void releaseForklift() {
        if (forkliftHeld) {
            forkliftHeld = false;
            forklift.release();
        }
    }

    private void releaseWorker() {
        if (workerHeld) {
            workerHeld = false;
            warehouseWorker.release();
        }
    }

    private void releaseHeld() {
        releaseWorker();
        releaseForklift();
    }

    @PreDestroy
    public synchronized void shutdown() {
        // El cierre invalida callbacks de la simulación y devuelve sus permisos aislados si aún están retenidos.
        if (current.active()) releaseHeld();
        waiting.clear();
        scheduler.shutdownNow();
    }

    public record ConflictSnapshot(String id, boolean active, boolean resolving, Instant startedAt, Instant expiresAt,
                                   ConflictOperation operationA, ConflictOperation operationB,
                                   String explanation, String message, String winnerId) {
        private static ConflictSnapshot idle() {
            return new ConflictSnapshot("", false, false, null, null, null, null, "",
                    "No hay un conflicto activo.", "");
        }

        private ConflictSnapshot withMessage(String nextMessage) {
            return new ConflictSnapshot(id, active, resolving, startedAt, expiresAt, operationA, operationB,
                    explanation, nextMessage, winnerId);
        }

        private ConflictSnapshot withResolution(String winner, String nextMessage) {
            return new ConflictSnapshot(id, active, true, startedAt, expiresAt, operationA, operationB,
                    explanation, nextMessage, winner);
        }

        private ConflictSnapshot resolved(String nextMessage) {
            return new ConflictSnapshot(id, false, false, startedAt, null, operationA, operationB,
                    explanation, nextMessage, winnerId);
        }

        private ConflictSnapshot cancelled() {
            return cancelledWithMessage("Conflicto cancelado; recursos liberados.");
        }

        private ConflictSnapshot cancelledWithMessage(String nextMessage) {
            if (operationA != null) {
                operationA.status = "Cancelada; recursos liberados";
                operationA.holds = "Ninguno";
                operationA.requests = "Ninguno";
            }
            if (operationB != null) {
                operationB.status = "Cancelada; recursos liberados";
                operationB.holds = "Ninguno";
                operationB.requests = "Ninguno";
            }
            return new ConflictSnapshot(id, false, false, startedAt, null, operationA, operationB,
                    explanation, nextMessage, "");
        }
    }

    public static final class ConflictOperation {
        private final String id;
        private final String name;
        private volatile String holds;
        private volatile String requests;
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
