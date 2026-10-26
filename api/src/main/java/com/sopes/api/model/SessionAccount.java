package com.sopes.api.model;

/** Identidad mínima guardada en sesión, sin credenciales ni hashes. */
public record SessionAccount(String id, String name, String email, AccountRole role, String customerId) { }
