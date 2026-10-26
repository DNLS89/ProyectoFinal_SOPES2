package com.sopes.api.model;

import java.util.Map;

/** Lote recibido cuya cantidad se distribuye entre ubicaciones físicas. */
public record StockLot(String id, String productCode, String merchandiseTypeCode, int quantity,
                       Map<WarehouseLocation, Integer> allocations) {
    public StockLot {
        if (id == null || id.isBlank() || productCode == null || productCode.isBlank()
                || merchandiseTypeCode == null || merchandiseTypeCode.isBlank()) {
            throw new IllegalArgumentException("El identificador del lote y el producto son obligatorios.");
        }
        if (quantity < 1) throw new IllegalArgumentException("La cantidad del lote debe ser positiva.");
        allocations = Map.copyOf(allocations);
        if (allocations.values().stream().anyMatch(units -> units == null || units < 1)
                || allocations.values().stream().mapToInt(Integer::intValue).sum() != quantity) {
            throw new IllegalArgumentException("Las ubicaciones deben contener cantidades positivas que sumen el lote.");
        }
        if (allocations.entrySet().stream().anyMatch(e -> e.getValue() > e.getKey().capacityUnits())) {
            throw new IllegalArgumentException("Una asignación excede la capacidad de su ubicación.");
        }
    }
}
