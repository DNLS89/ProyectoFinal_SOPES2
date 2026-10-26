package com.sopes.api.service;

import com.sopes.api.model.CustomerOrder;
import com.sopes.api.model.SharedResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Service
public class SharedResourceService {
    private final Map<String, SharedResource> definitions = new HashMap<>();
    private final Map<String, Semaphore> permits = new HashMap<>();
    private final Map<String, Set<String>> holders = new ConcurrentHashMap<>();

    public SharedResourceService(List<SharedResource> resources) {
        for (SharedResource resource : resources) {
            definitions.put(resource.code(), resource);
            permits.put(resource.code(), new Semaphore(resource.capacity(), true));
            holders.put(resource.code(), ConcurrentHashMap.newKeySet());
        }
    }

    public Lease acquire(CustomerOrder order) throws InterruptedException {
        List<String> codes = order.lines().stream()
                .flatMap(line -> line.merchandiseType().requiredResourceCodes().stream())
                .distinct().sorted().toList();
        List<String> acquired = new ArrayList<>();
        try {
            for (String code : codes) {
                Semaphore semaphore = permits.get(code);
                if (semaphore == null) throw new IllegalArgumentException("Recurso sin configurar: " + code);
                semaphore.acquire();
                acquired.add(code);
                holders.get(code).add(order.id());
            }
            return new Lease(order.id(), acquired);
        } catch (InterruptedException | RuntimeException e) {
            release(order.id(), acquired);
            throw e;
        }
    }

    public List<ResourceState> snapshot() {
        return definitions.values().stream().sorted(Comparator.comparing(SharedResource::code))
                .map(resource -> new ResourceState(resource, resource.capacity() - permits.get(resource.code()).availablePermits(),
                        holders.get(resource.code()).stream().sorted().toList()))
                .toList();
    }

    private void release(String orderId, List<String> codes) {
        for (int i = codes.size() - 1; i >= 0; i--) {
            String code = codes.get(i);
            holders.get(code).remove(orderId);
            permits.get(code).release();
        }
    }

    public record ResourceState(SharedResource resource, int inUse, List<String> orderIds) { }

    public final class Lease implements AutoCloseable {
        private final String orderId;
        private final List<String> acquiredCodes;
        private boolean closed;

        private Lease(String orderId, List<String> acquiredCodes) {
            this.orderId = orderId;
            this.acquiredCodes = List.copyOf(acquiredCodes);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            release(orderId, acquiredCodes);
        }
    }
}
