package com.sopes.api.model;

import java.time.Instant;
import java.util.List;

public record CustomerOrder(String id, Customer customer, ServiceLevel serviceLevel,
                            List<OrderLine> lines, OrderStatus status, Instant createdAt,
                            Instant queueEnteredAt, Instant statusChangedAt) {
    public CustomerOrder {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("El identificador del pedido es obligatorio.");
        if (customer == null || serviceLevel == null || status == null) {
            throw new IllegalArgumentException("Cliente, nivel de servicio y estado son obligatorios.");
        }
        lines = List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("El pedido debe incluir al menos un producto.");
        if (createdAt == null) throw new IllegalArgumentException("La fecha de creación es obligatoria.");
        if (statusChangedAt == null) throw new IllegalArgumentException("La fecha del estado es obligatoria.");
    }

    public int getTotalUnits() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }

    public CustomerOrder withStatus(OrderStatus newStatus, Instant queueEntry) {
        return new CustomerOrder(id, customer, serviceLevel, lines, newStatus, createdAt, queueEntry, Instant.now());
    }
}
