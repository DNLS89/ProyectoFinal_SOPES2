package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.Customer;
import com.sopes.api.model.OrderLine;
import com.sopes.api.model.OrderStatus;
import com.sopes.api.model.QueuePolicy;
import com.sopes.api.model.ServiceLevel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Registro central de pedidos y cola visible; no inicia procesamiento. */
@Service
public class WaitingOrderService {
    private final Map<String, CustomerOrder> orders = new LinkedHashMap<>();
    private QueuePolicy policy = QueuePolicy.SERVICE_PRIORITY;

    public synchronized CustomerOrder create(Customer customer,
                                             ServiceLevel level,
                                             List<OrderLine> lines, InventoryService inventory) {
        Instant now = Instant.now();
        boolean stocked = lines.stream().allMatch(line -> hasLineStock(line, inventory));
        OrderStatus status = stocked ? OrderStatus.QUEUED : OrderStatus.WAITING_FOR_STOCK;
        CustomerOrder order = new CustomerOrder("ORD-" + UUID.randomUUID(), customer, level, lines,
                status, now, stocked ? now : null, now);
        orders.put(order.id(), order);
        return order;
    }

    public synchronized void reconsider(InventoryService inventory) {
        for (var entry : new ArrayList<>(orders.entrySet())) {
            CustomerOrder order = entry.getValue();
            if (order.status() == OrderStatus.WAITING_FOR_STOCK
                    && order.lines().stream().allMatch(line -> hasLineStock(line, inventory))) {
                orders.put(entry.getKey(), order.withStatus(OrderStatus.QUEUED, Instant.now()));
            }
        }
    }

    public synchronized void setPolicy(QueuePolicy policy) {
        if (policy == null) throw new IllegalArgumentException("Seleccione una política válida.");
        this.policy = policy;
    }

    public synchronized QueuePolicy getPolicy() { return policy; }

    public synchronized List<CustomerOrder> findWaitingQueue() {
        return orders.values().stream().filter(order -> order.status() == OrderStatus.QUEUED)
                .sorted(queueComparator()).toList();
    }

    public synchronized List<CustomerOrder> findWaitingForStock() {
        return orders.values().stream().filter(order -> order.status() == OrderStatus.WAITING_FOR_STOCK).toList();
    }

    public synchronized List<CustomerOrder> findAll() { return List.copyOf(orders.values()); }

    public synchronized OrderStatistics statistics() {
        int total = orders.size();
        int queued = (int) orders.values().stream().filter(order -> order.status() == OrderStatus.QUEUED).count();
        int waiting = (int) orders.values().stream().filter(order -> order.status() == OrderStatus.WAITING_FOR_STOCK).count();
        int units = orders.values().stream().mapToInt(CustomerOrder::getTotalUnits).sum();
        double averageWait = orders.values().stream().filter(order -> order.queueEnteredAt() != null)
                .mapToLong(order -> java.time.Duration.between(order.createdAt(), order.queueEnteredAt()).toSeconds())
                .average().orElse(0);
        return new OrderStatistics(total, queued, waiting, units, averageWait);
    }

    private Comparator<CustomerOrder> queueComparator() {
        Comparator<CustomerOrder> fifo = Comparator.comparing(CustomerOrder::queueEnteredAt)
                .thenComparing(CustomerOrder::id);
        return switch (policy) {
            case SERVICE_PRIORITY -> Comparator.comparingInt((CustomerOrder order) -> order.serviceLevel().getLevel())
                    .thenComparing(fifo);
            case LONGEST_WAIT -> fifo;
            case SMALLEST_WORKLOAD -> Comparator.comparingInt(CustomerOrder::getTotalUnits).thenComparing(fifo);
        };
    }

    private boolean hasLineStock(OrderLine line, InventoryService inventory) {
        return inventory.getProductStock(line.productCode(), line.merchandiseType()) >= line.quantity();
    }

    public record OrderStatistics(int totalOrders, int queuedOrders, int waitingForStock,
                                  int totalUnits, double averageQueueEntryDelaySeconds) { }
}
