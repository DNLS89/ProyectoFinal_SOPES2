package com.sopes.api.service;

import com.sopes.api.model.WarehouseLocation;
import org.springframework.stereotype.Service;

import java.util.List;

/** Define la capacidad total y las ubicaciones físicas disponibles del almacén. */
@Service
public class WarehouseConfiguration {
    public static final int CAPACITY_UNITS = 1_000;
    private final List<WarehouseLocation> locations = List.of(
            new WarehouseLocation("A-01", 250),
            new WarehouseLocation("A-02", 250),
            new WarehouseLocation("B-01", 250),
            new WarehouseLocation("B-02", 250)
    );

    public int getCapacityUnits() { return CAPACITY_UNITS; }
    public List<WarehouseLocation> findLocations() { return locations; }
}
