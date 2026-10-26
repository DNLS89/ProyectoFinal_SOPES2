package com.sopes.api.model;

public enum QueuePolicy {
    SERVICE_PRIORITY("Prioridad de servicio"),
    LONGEST_WAIT("Mayor tiempo de espera"),
    SMALLEST_WORKLOAD("Menor cantidad de unidades");

    private final String displayName;
    QueuePolicy(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}
