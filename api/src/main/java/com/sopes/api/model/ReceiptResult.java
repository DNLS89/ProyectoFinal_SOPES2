package com.sopes.api.model;

import java.time.Instant;
import java.util.Map;

public record ReceiptResult(String receiptId, ReceiptStatus status, int requestedUnits, int acceptedUnits,
                            String message, Instant processedAt, Map<String, Integer> allocations) {
    public ReceiptResult {
        allocations = Map.copyOf(allocations);
    }
}
