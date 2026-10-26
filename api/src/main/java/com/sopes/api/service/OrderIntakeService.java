package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.OrderLine;
import com.sopes.api.model.ServiceLevel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderIntakeService {
    private final ProductCatalog products;
    private final CustomerRegistry customers;
    private final InventoryService inventory;
    private final WaitingOrderService orders;

    public OrderIntakeService(ProductCatalog products, CustomerRegistry customers, InventoryService inventory, WaitingOrderService orders) {
        this.products = products;
        this.customers = customers;
        this.inventory = inventory;
        this.orders = orders;
    }

    public CustomerOrder create(String customerId, String customerName, String email, ServiceLevel level,
                                List<String> productCodes, List<Integer> quantities) {
        if (level == null) throw new IllegalArgumentException("Seleccione el nivel de servicio.");
        if (productCodes == null || quantities == null || productCodes.isEmpty()
                || productCodes.size() != quantities.size()) {
            throw new IllegalArgumentException("Revise las líneas de productos del pedido.");
        }
        var customer = customers.resolve(customerId, customerName, email);
        List<OrderLine> lines = java.util.stream.IntStream.range(0, productCodes.size())
                .filter(index -> productCodes.get(index) != null && !productCodes.get(index).isBlank())
                .mapToObj(index -> {
                    var product = products.findByCode(productCodes.get(index).trim());
                    return new OrderLine(product.code(), product.name(), product.merchandiseType(), quantities.get(index));
                })
                .toList();
        if (lines.isEmpty()) throw new IllegalArgumentException("Agregue al menos un producto al pedido.");
        return orders.create(customer, level, lines, inventory);
    }
}
