package com.sopes.api.service;

import com.sopes.api.model.Customer;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerRegistry {
    private final Map<String, Customer> customersById = new LinkedHashMap<>();
    private final Map<String, String> idsByEmail = new LinkedHashMap<>();

    public CustomerRegistry() {
        register("Cliente demostración", "cliente@redxela.local");
        register("Comercio Central", "compras@central.local");
        register("Tienda Xelajú", "pedidos@xelaju.local");
    }

    public synchronized Customer resolve(String customerId, String name, String email) {
        if (customerId != null && !customerId.isBlank() && !"NEW".equals(customerId)) {
            Customer customer = customersById.get(customerId);
            if (customer == null) throw new IllegalArgumentException("La cuenta seleccionada no existe.");
            return customer;
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre del cliente es obligatorio.");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("El correo electrónico es obligatorio.");
        String key = normalizeEmail(email);
        String existingId = idsByEmail.get(key);
        if (existingId != null) return customersById.get(existingId);
        return register(name.trim(), email.trim());
    }

    public synchronized List<Customer> findAll() { return List.copyOf(customersById.values()); }

    private Customer register(String name, String email) {
        Customer customer = new Customer("CUS-" + UUID.randomUUID(), name, email);
        String key = normalizeEmail(email);
        String existingId = idsByEmail.get(key);
        if (existingId != null) return customersById.get(existingId);
        customersById.put(customer.id(), customer);
        idsByEmail.put(key, customer.id());
        return customer;
    }

    private String normalizeEmail(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }
}
