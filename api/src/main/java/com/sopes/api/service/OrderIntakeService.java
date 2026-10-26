package com.sopes.api.service;

import com.sopes.api.model.Customer;
import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.OrderLine;
import com.sopes.api.model.ServiceLevel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OrderIntakeService {
    private final MerchandiseCatalog catalog;
    private final InventoryService inventory;
    private final WaitingOrderService orders;

    public OrderIntakeService(MerchandiseCatalog catalog, InventoryService inventory, WaitingOrderService orders) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.orders = orders;
    }

    public CustomerOrder create(String customerName, String email, ServiceLevel level, List<String> productCodes,
                                List<String> productNames, List<String> merchandiseCodes, List<Integer> quantities) {
        if (customerName == null || customerName.isBlank()) throw new IllegalArgumentException("El nombre del cliente es obligatorio.");
        Customer customer = new Customer("CUS-" + UUID.randomUUID(), customerName.trim(), email == null ? "" : email.trim());
        if (level == null) throw new IllegalArgumentException("Seleccione el nivel de servicio.");
        if (productCodes == null || productNames == null || merchandiseCodes == null || quantities == null
                || productCodes.isEmpty() || productCodes.size() != productNames.size()
                || productCodes.size() != merchandiseCodes.size() || productCodes.size() != quantities.size()) {
            throw new IllegalArgumentException("Revise las líneas de productos del pedido.");
        }
        List<OrderLine> lines = java.util.stream.IntStream.range(0, productCodes.size())
                .filter(index -> productCodes.get(index) != null && !productCodes.get(index).isBlank())
                .mapToObj(index -> new OrderLine(productCodes.get(index).trim(), productNames.get(index).trim(),
                        catalog.findByCode(merchandiseCodes.get(index)), quantities.get(index)))
                .toList();
        if (lines.isEmpty()) throw new IllegalArgumentException("Agregue al menos un producto al pedido.");
        return orders.create(customer, level, lines, inventory);
    }
}
