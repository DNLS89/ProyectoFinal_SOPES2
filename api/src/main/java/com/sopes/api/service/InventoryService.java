package com.sopes.api.service;

import com.sopes.api.model.MerchandiseType;
import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.InventoryReservation;
import com.sopes.api.model.OrderLine;
import com.sopes.api.model.Product;
import com.sopes.api.model.ProductInventory;
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

/** Sincroniza existencias, reservas, lotes y ocupación de las ubicaciones. */
@Service
public class InventoryService {
    private final WarehouseConfiguration warehouse;
    private final List<StockLot> lots = new ArrayList<>();
    private final Map<String, Integer> locationUse = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> availableByLot = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> storedByLotLocation = new LinkedHashMap<>();
    private final Map<String, InventoryReservation> activeReservations = new LinkedHashMap<>();
    private int storedUnits;

    public InventoryService(WarehouseConfiguration warehouse) {
        this.warehouse = warehouse;
        warehouse.findLocations().forEach(location -> locationUse.put(location.code(), 0));
    }

    public synchronized ReceiptResult store(ReceiptRequest request) {
        // Un solo bloqueo mantiene alineados capacidad, ubicaciones y lotes incluso ante recepciones simultáneas.
        int accepted = Math.min(request.quantity(), warehouse.getCapacityUnits() - storedUnits);
        Map<WarehouseLocation, Integer> allocations = new LinkedHashMap<>();
        int remaining = accepted;
        // El recorrido fijo reparte el lote si la primera ubicación no tiene espacio suficiente.
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
            storedByLotLocation.put(id, new LinkedHashMap<>(availableLocations));
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

    public synchronized List<StockLot> findLots() {
        Map<String, WarehouseLocation> locations = new LinkedHashMap<>();
        warehouse.findLocations().forEach(location -> locations.put(location.code(), location));
        return lots.stream().map(lot -> {
            Map<WarehouseLocation, Integer> allocations = new LinkedHashMap<>();
            storedByLotLocation.getOrDefault(lot.id(), Map.of()).forEach((code, units) -> {
                if (units > 0) allocations.put(locations.get(code), units);
            });
            int quantity = allocations.values().stream().mapToInt(Integer::intValue).sum();
            return quantity == 0 ? null : new StockLot(lot.id(), lot.productCode(), lot.merchandiseTypeCode(), quantity, allocations);
        }).filter(java.util.Objects::nonNull).toList();
    }
    public synchronized int getStoredUnits() { return storedUnits; }
    public int getCapacityUnits() { return warehouse.getCapacityUnits(); }

    public synchronized Map<String, Integer> stockByLocation() { return Map.copyOf(locationUse); }

    public synchronized List<ProductInventory> findProductInventory(List<Product> products) {
        List<ProductInventory> result = new ArrayList<>();
        for (Product product : products) {
            int available = 0;
            int stored = 0;
            Map<String, Integer> byLocation = new LinkedHashMap<>();
            for (StockLot lot : lots) {
                if (!matches(lot, new StockKey(product.code(), product.merchandiseType().code()))) continue;
                available += availableByLot.getOrDefault(lot.id(), Map.of()).values().stream()
                        .mapToInt(Integer::intValue).sum();
                storedByLotLocation.getOrDefault(lot.id(), Map.of()).forEach((location, units) -> {
                    if (units > 0) byLocation.merge(location, units, Integer::sum);
                });
            }
            stored = byLocation.values().stream().mapToInt(Integer::intValue).sum();
            result.add(new ProductInventory(product, available, stored - available, stored, byLocation));
        }
        return List.copyOf(result);
    }

    public synchronized int getProductStock(String productCode, MerchandiseType type) {
        return lots.stream().filter(lot -> lot.productCode().equals(productCode)
                        && lot.merchandiseTypeCode().equals(type.code()))
                .mapToInt(lot -> availableByLot.getOrDefault(lot.id(), Map.of()).values().stream()
                        .mapToInt(Integer::intValue).sum()).sum();
    }

    public synchronized boolean hasStock(List<OrderLine> lines) {
        // La consulta usa el mismo monitor que reservar para observar disponibilidad coherente.
        Map<StockKey, Integer> required = requiredStock(lines);
        return required.entrySet().stream().allMatch(entry -> getAvailable(entry.getKey()) >= entry.getValue());
    }

    public synchronized InventoryReservation reserve(CustomerOrder order) {
        // Validación y descuento son indivisibles: dos workers no pueden reclamar las mismas unidades.
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
        // Si el procesamiento se reintenta, se restaura la reserva activa una sola vez y no se duplica stock.
        InventoryReservation active = activeReservations.remove(reservation.id());
        if (active == null) return;
        active.allocationsByLot().forEach((lotId, locations) -> locations.forEach((code, units) ->
                availableByLot.get(lotId).merge(code, units, Integer::sum)));
    }

    public synchronized void complete(InventoryReservation reservation) {
        // Al completar, se consume la reserva y se libera ocupación física para mantener lotes y capacidad alineados.
        InventoryReservation active = activeReservations.remove(reservation.id());
        if (active == null) return;
        active.allocationsByLot().forEach((lotId, locations) -> locations.forEach((code, units) -> {
            locationUse.compute(code, (ignored, current) -> current - units);
            Map<String, Integer> physical = storedByLotLocation.get(lotId);
            int left = physical.get(code) - units;
            if (left == 0) physical.remove(code); else physical.put(code, left);
        }));
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
