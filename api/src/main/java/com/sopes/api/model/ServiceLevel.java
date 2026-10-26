package com.sopes.api.model;

public enum ServiceLevel {
    EXPRESS(1, "Exprés", 0),
    PRIORITY(2, "Prioritario", 10),
    STANDARD(3, "Estándar", 30),
    SCHEDULED(4, "Programado", 60),
    ECONOMIC(5, "Económico", 120);

    private final int level;
    private final String displayName;
    private final int targetWaitMinutes;

    ServiceLevel(int level, String displayName, int targetWaitMinutes) {
        this.level = level;
        this.displayName = displayName;
        this.targetWaitMinutes = targetWaitMinutes;
    }

    public int getLevel() { return level; }
    public String getDisplayName() { return displayName; }
    public int getTargetWaitMinutes() { return targetWaitMinutes; }
}
