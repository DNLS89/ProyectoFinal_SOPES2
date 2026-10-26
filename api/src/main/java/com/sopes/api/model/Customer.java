package com.sopes.api.model;

/** Cuenta identificable que puede reutilizarse en varios pedidos. */
public record Customer(String id, String name, String email) {
    public Customer {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("El identificador y nombre del cliente son obligatorios.");
        }
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("El correo electrónico no es válido.");
        }
    }
}
