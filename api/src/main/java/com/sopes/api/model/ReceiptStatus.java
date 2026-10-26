package com.sopes.api.model;

public enum ReceiptStatus {
    QUEUED("En cola"), PARTIALLY_ACCEPTED("Aceptada parcialmente"), ACCEPTED("Aceptada"),
    REJECTED("Rechazada"), FAILED("Error de procesamiento");

    private final String displayName;
    ReceiptStatus(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}
