package com.sopes.api.model;

/** Datos validados que recepción envía a la cola de inventario. */
public record ReceiptRequest(String productCode, MerchandiseType merchandiseType, int quantity) {
    public ReceiptRequest {
        if (productCode == null || productCode.isBlank()) throw new IllegalArgumentException("Seleccione un producto.");
        if (merchandiseType == null) throw new IllegalArgumentException("Seleccione el tipo de mercancía.");
        if (quantity < 1) throw new IllegalArgumentException("La cantidad debe ser positiva.");
    }
}
