package com.sopes.api.model;

import java.time.Instant;
import java.util.List;

public record CustomerOrder(String id, Customer customer, ServiceLevel serviceLevel,
                            List<OrderLine> lines, OrderStatus status, Instant createdAt,
                            Instant queueEnteredAt, Instant statusChangedAt, Instant startedAt,
                            Instant completedAt, int progressPercent, long estimatedRemainingSeconds) {
    public CustomerOrder {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("El identificador del pedido es obligatorio.");
        if (customer == null || serviceLevel == null || status == null) {
            throw new IllegalArgumentException("Cliente, nivel de servicio y estado son obligatorios.");
        }
        lines = List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("El pedido debe incluir al menos un producto.");
        if (createdAt == null) throw new IllegalArgumentException("La fecha de creación es obligatoria.");
        if (statusChangedAt == null) throw new IllegalArgumentException("La fecha del estado es obligatoria.");
        if (progressPercent < 0 || progressPercent > 100 || estimatedRemainingSeconds < 0) {
            throw new IllegalArgumentException("El progreso o tiempo restante no es válido.");
        }
    }

    public int getTotalUnits() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    public CustomerOrder withStatus(OrderStatus newStatus, Instant queueEntry) {
        Instant now = Instant.now();
        Instant start = newStatus == OrderStatus.PROCESSING ? now : startedAt;
        Instant completion = newStatus == OrderStatus.COMPLETED ? now : completedAt;
        int progress = newStatus == OrderStatus.COMPLETED ? 100
                : newStatus == OrderStatus.QUEUED || newStatus == OrderStatus.WAITING_FOR_STOCK ? 0 : progressPercent;
        long remaining = newStatus == OrderStatus.PROCESSING ? (getTotalUnits() + 1L) / 2L : 0;
        return new CustomerOrder(id, customer, serviceLevel, lines, newStatus, createdAt, queueEntry, now,
                start, completion, progress, remaining);
    }

    public CustomerOrder withProgress(int progress, long remainingSeconds) {
        return new CustomerOrder(id, customer, serviceLevel, lines, status, createdAt, queueEnteredAt,
                statusChangedAt, startedAt, completedAt, progress, remainingSeconds);
    }
}
