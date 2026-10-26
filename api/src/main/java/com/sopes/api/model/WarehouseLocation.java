package com.sopes.api.model;

/** Ubicación física con capacidad propia dentro del almacén. */
public record WarehouseLocation(String code, int capacityUnits) {
    public WarehouseLocation {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("El código de ubicación es obligatorio.");
        if (capacityUnits < 1) throw new IllegalArgumentException("La capacidad de ubicación debe ser positiva.");
    }
}
