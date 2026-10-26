package com.sopes.api.service;

import com.sopes.api.model.MerchandiseType;
import com.sopes.api.model.ReceiptRequest;
import com.sopes.api.model.ReceiptResult;
import com.sopes.api.model.ReceiptStatus;
import com.sopes.api.model.StockLot;
import com.sopes.api.model.WarehouseLocation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {
    private final WarehouseConfiguration warehouse;
    private final List<StockLot> lots = new ArrayList<>();
    private final Map<String, Integer> locationUse = new LinkedHashMap<>();
    private int storedUnits;

    public InventoryService(WarehouseConfiguration warehouse) {
        this.warehouse = warehouse;
        warehouse.findLocations().forEach(location -> locationUse.put(location.code(), 0));
    }

    public synchronized ReceiptResult store(ReceiptRequest request) {
        int accepted = Math.min(request.quantity(), warehouse.getCapacityUnits() - storedUnits);
        Map<WarehouseLocation, Integer> allocations = new LinkedHashMap<>();
        int remaining = accepted;
        for (WarehouseLocation location : warehouse.findLocations()) {
            int free = location.capacityUnits() - locationUse.get(location.code());
            int allocated = Math.min(free, remaining);
            if (allocated > 0) {
                allocations.put(location, allocated);
                remaining -= allocated;
                if (remaining == 0) break;
            }
        }

        if (accepted > 0) {
            String id = UUID.randomUUID().toString();
            StockLot lot = new StockLot(id, request.productCode(), request.merchandiseType().code(), accepted, allocations);
            lots.add(lot);
            allocations.forEach((location, units) -> locationUse.merge(location.code(), units, Integer::sum));
            storedUnits += accepted;
        }

        ReceiptStatus status = accepted == 0 ? ReceiptStatus.REJECTED
                : accepted == request.quantity() ? ReceiptStatus.ACCEPTED : ReceiptStatus.PARTIALLY_ACCEPTED;
        String message = accepted == request.quantity() ? "Mercadería almacenada."
                : accepted == 0 ? "No hay capacidad disponible; no se recibió mercadería."
                : "Capacidad parcial: se almacenaron " + accepted + " de " + request.quantity() + " unidades.";
        Map<String, Integer> byLocation = new LinkedHashMap<>();
        allocations.forEach((location, units) -> byLocation.put(location.code(), units));
        return new ReceiptResult("REC-" + UUID.randomUUID(), status, request.quantity(), accepted, message,
                Instant.now(), byLocation);
    }

    public synchronized List<StockLot> findLots() { return List.copyOf(lots); }
    public synchronized int getStoredUnits() { return storedUnits; }
    public int getCapacityUnits() { return warehouse.getCapacityUnits(); }

    public synchronized Map<String, Integer> stockByLocation() { return Map.copyOf(locationUse); }

    public synchronized int getProductStock(String productCode, MerchandiseType type) {
        return lots.stream().filter(lot -> lot.productCode().equals(productCode)
                        && lot.merchandiseTypeCode().equals(type.code()))
                .mapToInt(StockLot::quantity).sum();
    }
}
