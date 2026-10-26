package com.sopes.api.model;

/** Elemento del catálogo fijo que determina la clasificación de mercancía. */
public record Product(String code, String name, MerchandiseType merchandiseType) {
    public Product {
        if (code == null || code.isBlank() || name == null || name.isBlank() || merchandiseType == null) {
            throw new IllegalArgumentException("Código, nombre y tipo de mercancía son obligatorios.");
        }
    }
}
