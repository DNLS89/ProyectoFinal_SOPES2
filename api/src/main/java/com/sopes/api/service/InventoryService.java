package com.sopes.api.service;

import com.sopes.api.model.MerchandiseType;
import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.InventoryReservation;
import com.sopes.api.model.OrderLine;
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
    private final Map<String, Map<String, Integer>> availableByLot = new LinkedHashMap<>();
    private final Map<String, InventoryReservation> activeReservations = new LinkedHashMap<>();
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
            Map<String, Integer> availableLocations = new LinkedHashMap<>();
            allocations.forEach((location, units) -> availableLocations.put(location.code(), units));
            availableByLot.put(id, availableLocations);
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
                .mapToInt(lot -> availableByLot.getOrDefault(lot.id(), Map.of()).values().stream()
                        .mapToInt(Integer::intValue).sum()).sum();
    }

    public synchronized boolean hasStock(List<OrderLine> lines) {
        Map<StockKey, Integer> required = requiredStock(lines);
        return required.entrySet().stream().allMatch(entry -> getAvailable(entry.getKey()) >= entry.getValue());
    }

    public synchronized InventoryReservation reserve(CustomerOrder order) {
        Map<StockKey, Integer> required = requiredStock(order.lines());
        if (required.entrySet().stream().anyMatch(entry -> getAvailable(entry.getKey()) < entry.getValue())) return null;

        Map<String, Map<String, Integer>> allocations = new LinkedHashMap<>();
        for (var need : required.entrySet()) {
            int remaining = need.getValue();
            for (StockLot lot : lots) {
                if (!matches(lot, need.getKey())) continue;
                Map<String, Integer> locationStock = availableByLot.get(lot.id());
                for (var location : new ArrayList<>(locationStock.entrySet())) {
                    int amount = Math.min(location.getValue(), remaining);
                    if (amount == 0) continue;
                    allocations.computeIfAbsent(lot.id(), ignored -> new LinkedHashMap<>())
                            .merge(location.getKey(), amount, Integer::sum);
                    remaining -= amount;
                    if (remaining == 0) break;
                }
                if (remaining == 0) break;
            }
        }

        allocations.forEach((lotId, locations) -> locations.forEach((code, units) -> {
            Map<String, Integer> available = availableByLot.get(lotId);
            int left = available.get(code) - units;
            if (left == 0) available.remove(code); else available.put(code, left);
        }));
        InventoryReservation reservation = new InventoryReservation(UUID.randomUUID().toString(), allocations);
        activeReservations.put(reservation.id(), reservation);
        return reservation;
    }

    public synchronized void release(InventoryReservation reservation) {
        InventoryReservation active = activeReservations.remove(reservation.id());
        if (active == null) return;
        active.allocationsByLot().forEach((lotId, locations) -> locations.forEach((code, units) ->
                availableByLot.get(lotId).merge(code, units, Integer::sum)));
    }

    public synchronized void complete(InventoryReservation reservation) {
        InventoryReservation active = activeReservations.remove(reservation.id());
        if (active == null) return;
        active.allocationsByLot().values().forEach(locations -> locations.forEach((code, units) ->
                locationUse.compute(code, (ignored, current) -> current - units)));
        int consumed = active.allocationsByLot().values().stream().flatMap(locations -> locations.values().stream())
                .mapToInt(Integer::intValue).sum();
        storedUnits -= consumed;
    }

    private int getAvailable(StockKey key) {
        return lots.stream().filter(lot -> matches(lot, key))
                .mapToInt(lot -> availableByLot.getOrDefault(lot.id(), Map.of()).values().stream()
                        .mapToInt(Integer::intValue).sum()).sum();
    }

    private Map<StockKey, Integer> requiredStock(List<OrderLine> lines) {
        Map<StockKey, Integer> required = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            required.merge(new StockKey(line.productCode(), line.merchandiseType().code()), line.quantity(), Integer::sum);
        }
        return required;
    }

    private boolean matches(StockLot lot, StockKey key) {
        return lot.productCode().equals(key.productCode()) && lot.merchandiseTypeCode().equals(key.merchandiseTypeCode());
    }

    private record StockKey(String productCode, String merchandiseTypeCode) { }
}
