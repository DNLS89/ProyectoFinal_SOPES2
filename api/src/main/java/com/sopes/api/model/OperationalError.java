package com.sopes.api.model;

import java.time.Instant;

/** Error operativo breve que se muestra en el tablero con su origen y hora. */
public record OperationalError(String source, String reference, String message, Instant occurredAt) { }
