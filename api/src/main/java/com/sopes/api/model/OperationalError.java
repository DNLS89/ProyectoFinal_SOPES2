package com.sopes.api.model;

import java.time.Instant;

public record OperationalError(String source, String reference, String message, Instant occurredAt) { }
