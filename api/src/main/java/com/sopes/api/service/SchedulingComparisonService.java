package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.QueuePolicy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Proyecta las políticas sobre la misma carga capturada y los mismos trabajadores simulados. */
@Service
public class SchedulingComparisonService {
    private static final int SIMULATED_WORKERS = 4;
    private ComparisonSnapshot latest = new ComparisonSnapshot(Instant.EPOCH, 0, SIMULATED_WORKERS, List.of());

    public synchronized ComparisonSnapshot compare(List<CustomerOrder> sameWorkload) {
        Instant capturedAt = Instant.now();
        List<PolicyResult> results = java.util.Arrays.stream(QueuePolicy.values())
                .map(policy -> simulate(policy, sameWorkload, capturedAt)).toList();
        latest = new ComparisonSnapshot(capturedAt, sameWorkload.size(), SIMULATED_WORKERS, results);
        return latest;
    }

    public synchronized ComparisonSnapshot latest() { return latest; }

    private PolicyResult simulate(QueuePolicy policy, List<CustomerOrder> workload, Instant capturedAt) {
        // La cola de disponibilidad asigna cada pedido al trabajador simulado que queda libre primero.
        List<CustomerOrder> ordered = workload.stream().sorted(comparator(policy)).toList();
        PriorityQueue<Double> workers = new PriorityQueue<>();
        for (int i = 0; i < SIMULATED_WORKERS; i++) workers.add(0.0);
        double totalWait = 0;
        double totalProcessing = 0;
        double totalCompletion = 0;
        long targetsMet = 0;
        for (CustomerOrder order : ordered) {
            double workerAvailable = workers.remove();
            double alreadyWaited = order.queueEnteredAt() == null ? 0
                    : Math.max(0, Duration.between(order.queueEnteredAt(), capturedAt).toMillis() / 1000.0);
            double processing = order.getTotalUnits() * 0.5;
            double wait = alreadyWaited + workerAvailable;
            double completion = wait + processing;
            totalWait += wait;
            totalProcessing += processing;
            totalCompletion += completion;
            if (wait <= order.serviceLevel().getTargetWaitMinutes() * 60.0) targetsMet++;
            workers.add(workerAvailable + processing);
        }
        int count = ordered.size();
        return new PolicyResult(policy, count, mean(totalWait, count), mean(totalProcessing, count),
                mean(totalCompletion, count), targetsMet, count);
    }

    private Comparator<CustomerOrder> comparator(QueuePolicy policy) {
        Comparator<CustomerOrder> fifo = Comparator.comparing(CustomerOrder::queueEnteredAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(CustomerOrder::id);
        return switch (policy) {
            case SERVICE_PRIORITY -> Comparator.comparingInt((CustomerOrder order) -> order.serviceLevel().getLevel()).thenComparing(fifo);
            case LONGEST_WAIT -> fifo;
            case SMALLEST_WORKLOAD -> Comparator.comparingInt(CustomerOrder::getTotalUnits).thenComparing(fifo);
        };
    }

    private double mean(double sum, int count) { return count == 0 ? 0 : sum / count; }

    public record ComparisonSnapshot(Instant capturedAt, int workloadSize, int simulatedWorkers,
                                    List<PolicyResult> results) { }

    public record PolicyResult(QueuePolicy policy, int orders, double averageWaitSeconds,
                               double averageProcessingSeconds, double averageCompletionSeconds,
                               long targetsMet, long targetsEvaluated) { }
}
