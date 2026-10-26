package com.sopes.api.model;

public record Product(String code, String name, MerchandiseType merchandiseType) {
    public Product {
        if (code == null || code.isBlank() || name == null || name.isBlank() || merchandiseType == null) {
            throw new IllegalArgumentException("Código, nombre y tipo de mercancía son obligatorios.");
        }
    }
}
