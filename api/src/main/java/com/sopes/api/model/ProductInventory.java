package com.sopes.api.model;

import java.util.Map;

public record ProductInventory(Product product, int availableUnits, int reservedUnits, int storedUnits,
                               Map<String, Integer> unitsByLocation) {
    public ProductInventory {
        unitsByLocation = Map.copyOf(unitsByLocation);
    }
}
