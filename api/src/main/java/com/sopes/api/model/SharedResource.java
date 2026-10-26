package com.sopes.api.model;

/** Definición de un recurso compartido y su capacidad simultánea. */
public record SharedResource(String code, String name, int capacity) {
    public SharedResource {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("El código y el nombre del recurso son obligatorios.");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("La capacidad del recurso debe ser positiva.");
        }
    }
}
