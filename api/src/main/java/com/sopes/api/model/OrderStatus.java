package com.sopes.api.model;

/** Estados que describen la espera, el trabajo y el resultado de un pedido. */
public enum OrderStatus {
    WAITING_FOR_STOCK("Esperando inventario"),
    QUEUED("En espera"),
    PROCESSING("En proceso"),
    BLOCKED("Bloqueado"),
    COMPLETED("Completado"),
    CANCELLED("Cancelado");

    private final String displayName;

    OrderStatus(String displayName) { this.displayName = displayName; }

    public String getDisplayName() { return displayName; }
}
