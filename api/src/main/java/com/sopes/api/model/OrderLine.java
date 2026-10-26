package com.sopes.api.model;

public record OrderLine(String productCode, String productName, MerchandiseType merchandiseType, int quantity) {
    public OrderLine {
        if (productCode == null || productCode.isBlank() || productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("El código y nombre del producto son obligatorios.");
        }
        if (merchandiseType == null) throw new IllegalArgumentException("El tipo de mercancía es obligatorio.");
        if (quantity < 1) throw new IllegalArgumentException("La cantidad debe ser positiva.");
    }
}
