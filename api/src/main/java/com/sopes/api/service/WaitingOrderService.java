package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.Customer;
import com.sopes.api.model.InventoryReservation;
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

/** Mantiene estados y ordena la cola visible; el despachador reclama pedidos desde aquí. */
@Service
public class WaitingOrderService {
    private final Map<String, CustomerOrder> orders = new LinkedHashMap<>();
    private QueuePolicy policy = QueuePolicy.SERVICE_PRIORITY;

    public synchronized CustomerOrder create(Customer customer,
                                             ServiceLevel level,
                                             List<OrderLine> lines, InventoryService inventory) {
        Instant now = Instant.now();
        boolean stocked = inventory.hasStock(lines);
        OrderStatus status = stocked ? OrderStatus.QUEUED : OrderStatus.WAITING_FOR_STOCK;
        CustomerOrder order = new CustomerOrder("ORD-" + UUID.randomUUID(), customer, level, lines,
                status, now, stocked ? now : null, now, null, null, 0, 0, 0, false, false);
        orders.put(order.id(), order);
        return order;
    }

    public synchronized void reconsider(InventoryService inventory) {
        // Solo se reactivan pedidos con todas sus líneas disponibles para evitar reclamos parciales.
        for (var entry : new ArrayList<>(orders.entrySet())) {
            CustomerOrder order = entry.getValue();
            if (order.status() == OrderStatus.WAITING_FOR_STOCK
                    && inventory.hasStock(order.lines())) {
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
        // La cola se deriva de los estados y se ordena por política; el monitor protege la vista frente a cambios concurrentes.
        return orders.values().stream().filter(order -> order.status() == OrderStatus.QUEUED)
                .sorted(queueComparator()).toList();
    }

    public synchronized List<CustomerOrder> findWaitingForStock() {
        return orders.values().stream().filter(order -> order.status() == OrderStatus.WAITING_FOR_STOCK).toList();
    }

    public synchronized List<CustomerOrder> findAll() { return List.copyOf(orders.values()); }

    public synchronized List<CustomerOrder> findByCustomerId(String customerId) {
        return orders.values().stream().filter(order -> order.customer().id().equals(customerId)).toList();
    }

    public synchronized List<CustomerOrder> findActiveOrders() {
        return orders.values().stream().filter(order -> order.status() == OrderStatus.PROCESSING).toList();
    }

    public synchronized ProcessingClaim claimNext(InventoryService inventory) {
        // El monitor serializa reclamos y la reserva atómica evita que dos workers tomen el mismo pedido o stock.
        for (CustomerOrder order : findWaitingQueue()) {
            InventoryReservation reservation = inventory.reserve(order);
            if (reservation == null) {
                orders.put(order.id(), order.withStatus(OrderStatus.WAITING_FOR_STOCK, null));
                continue;
            }
            CustomerOrder processing = order.withStatus(OrderStatus.PROCESSING, order.queueEnteredAt());
            orders.put(order.id(), processing);
            return new ProcessingClaim(processing, reservation);
        }
        return null;
    }

    public synchronized void updateProgress(String orderId, int percent, long remainingSeconds) {
        CustomerOrder current = orders.get(orderId);
        if (current != null && current.status() == OrderStatus.PROCESSING) {
            orders.put(orderId, current.withProgress(percent, remainingSeconds));
        }
    }

    public synchronized void markStarted(String orderId) {
        CustomerOrder current = orders.get(orderId);
        if (current != null && current.status() == OrderStatus.PROCESSING && current.startedAt() == null) {
            orders.put(orderId, current.withProcessingStart(Instant.now()));
        }
    }

    public synchronized void complete(ProcessingClaim claim, InventoryService inventory) {
        inventory.complete(claim.reservation());
        CustomerOrder current = orders.get(claim.order().id());
        if (current != null && current.status() == OrderStatus.PROCESSING) {
            orders.put(current.id(), current.withStatus(OrderStatus.COMPLETED, current.queueEnteredAt()));
        }
    }

    public synchronized void returnToQueue(ProcessingClaim claim, InventoryService inventory) {
        inventory.release(claim.reservation());
        CustomerOrder current = orders.get(claim.order().id());
        if (current != null && current.status() == OrderStatus.PROCESSING) {
            orders.put(current.id(), current.withStatus(OrderStatus.QUEUED, current.queueEnteredAt()));
        }
    }

    public synchronized void block(ProcessingClaim claim, InventoryService inventory) {
        inventory.release(claim.reservation());
        CustomerOrder current = orders.get(claim.order().id());
        if (current != null && current.status() == OrderStatus.PROCESSING) {
            orders.put(current.id(), current.withStatus(OrderStatus.BLOCKED, current.queueEnteredAt()));
        }
    }

    public synchronized OrderStatistics statistics() {
        int total = orders.size();
        int queued = (int) orders.values().stream().filter(order -> order.status() == OrderStatus.QUEUED).count();
        int waiting = (int) orders.values().stream().filter(order -> order.status() == OrderStatus.WAITING_FOR_STOCK).count();
        int completed = (int) orders.values().stream().filter(order -> order.status() == OrderStatus.COMPLETED).count();
        int units = orders.values().stream().mapToInt(CustomerOrder::getTotalUnits).sum();
        double averageWait = orders.values().stream().filter(CustomerOrder::waitTargetMeasured)
                .mapToLong(CustomerOrder::queueWaitSeconds)
                .average().orElse(0);
        double averageProcessing = orders.values().stream().filter(order -> order.startedAt() != null && order.completedAt() != null)
                .mapToLong(order -> java.time.Duration.between(order.startedAt(), order.completedAt()).toSeconds())
                .average().orElse(0);
        long targetEvaluated = orders.values().stream().filter(CustomerOrder::waitTargetMeasured).count();
        long targetMet = orders.values().stream().filter(order -> order.waitTargetMeasured() && order.waitTargetMet()).count();
        return new OrderStatistics(total, queued, waiting, completed, units, averageWait, averageProcessing,
                targetMet, targetEvaluated);
    }

    private Comparator<CustomerOrder> queueComparator() {
        // No es una cola FIFO física: se ordena la vista por política y la antigüedad desempata de forma reproducible.
        Comparator<CustomerOrder> fifo = Comparator.comparing(CustomerOrder::queueEnteredAt)
                .thenComparing(CustomerOrder::id);
        return switch (policy) {
            case SERVICE_PRIORITY -> Comparator.comparingInt((CustomerOrder order) -> order.serviceLevel().getLevel())
                    .thenComparing(fifo);
            case LONGEST_WAIT -> fifo;
            case SMALLEST_WORKLOAD -> Comparator.comparingInt(CustomerOrder::getTotalUnits).thenComparing(fifo);
        };
    }

    public record ProcessingClaim(CustomerOrder order, InventoryReservation reservation) { }

    public record OrderStatistics(int totalOrders, int queuedOrders, int waitingForStock, int completedOrders,
                                  int totalUnits, double averageWaitSeconds,
                                  double averageProcessingSeconds, long waitTargetsMet,
                                  long waitTargetsEvaluated) { }
}
