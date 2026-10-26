package com.sopes.api.model;

import java.util.Map;

/** Identifica las unidades apartadas por lote y ubicación hasta completar o devolver el pedido. */
public record InventoryReservation(String id, Map<String, Map<String, Integer>> allocationsByLot) {
    public InventoryReservation {
        allocationsByLot = allocationsByLot.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }
}
