package com.sopes.api.model;

import java.util.Map;

public record InventoryReservation(String id, Map<String, Map<String, Integer>> allocationsByLot) {
    public InventoryReservation {
        allocationsByLot = allocationsByLot.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }
}
