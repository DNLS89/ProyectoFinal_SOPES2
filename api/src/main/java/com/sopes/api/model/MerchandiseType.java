package com.sopes.api.model;

import java.util.List;

/** Clasifica mercancía y declara qué permisos necesita su procesamiento. */
public record MerchandiseType(String code, String name, String description, List<String> requiredResourceCodes) {
    public MerchandiseType {
        if (code == null || code.isBlank() || name == null || name.isBlank()
                || description == null || description.isBlank()) {
            throw new IllegalArgumentException("El tipo de mercancía requiere código, nombre y descripción.");
        }
        requiredResourceCodes = List.copyOf(requiredResourceCodes);
    }
}
