package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.OrderLine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class WaitingOrderService {
    private final List<CustomerOrder> waitingForStock = new ArrayList<>();
    private final List<CustomerOrder> stockAvailable = new ArrayList<>();

    public synchronized void register(CustomerOrder order) {
        waitingForStock.add(order);
    }

    public synchronized void reconsider(InventoryService inventory) {
        var iterator = waitingForStock.iterator();
        while (iterator.hasNext()) {
            CustomerOrder order = iterator.next();
            boolean available = order.lines().stream().allMatch(line -> hasLineStock(line, inventory));
            if (available) {
                stockAvailable.add(order);
                iterator.remove();
            }
        }
    }

    public synchronized List<CustomerOrder> findWaitingForStock() { return List.copyOf(waitingForStock); }
    public synchronized List<CustomerOrder> findReadyForReevaluation() { return List.copyOf(stockAvailable); }

    private boolean hasLineStock(OrderLine line, InventoryService inventory) {
        return inventory.getProductStock(line.productCode(), line.merchandiseType()) >= line.quantity();
    }
}
