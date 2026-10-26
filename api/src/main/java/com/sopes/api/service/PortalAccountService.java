package com.sopes.api.service;

import com.sopes.api.model.AccountRole;
import com.sopes.api.model.Customer;
import com.sopes.api.model.SessionAccount;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Administra credenciales en memoria y devuelve a las sesiones solo identidades sin secretos. */
@Service
public class PortalAccountService {
    public static final String SESSION_ATTRIBUTE = "redxela.authenticatedAccount";

    private final CustomerRegistry customers;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, StoredAccount> accountsByEmail = new HashMap<>();

    public PortalAccountService(CustomerRegistry customers) {
        this.customers = customers;
    }

    public synchronized SessionAccount registerCustomer(String name, String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (accountsByEmail.containsKey(normalizedEmail)) {
            throw new IllegalArgumentException("Ya existe una cuenta con ese correo.");
        }
        String passwordHash = passwordEncoder.encode(password);
        Customer customer = customers.registerNew(name, email.trim());
        SessionAccount sessionAccount = new SessionAccount(customer.id(), customer.name(), customer.email(),
                AccountRole.CUSTOMER, customer.id());
        accountsByEmail.put(normalizedEmail, new StoredAccount(sessionAccount, passwordHash));
        return sessionAccount;
    }

    public synchronized SessionAccount authenticate(String email, String password) {
        if (email == null || password == null) return null;
        StoredAccount account = accountsByEmail.get(normalizeEmail(email));
        if (account == null || !passwordEncoder.matches(password, account.passwordHash())) return null;
        return account.sessionAccount();
    }

    /** Solo lo invoca el bootstrap interno; no existe una ruta pública para crear operadores. */
    public synchronized void provisionOperator(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (customers.emailExists(email)) {
            throw new IllegalStateException("El operador debe usar un correo distinto a las cuentas de demostración.");
        }
        if (accountsByEmail.containsKey(normalizedEmail)) {
            throw new IllegalStateException("El correo configurado para operador ya está registrado.");
        }
        SessionAccount account = new SessionAccount("OPR-" + UUID.randomUUID(), "Operador REDXela",
                email.trim(), AccountRole.OPERATOR, null);
        accountsByEmail.put(normalizedEmail, new StoredAccount(account, passwordEncoder.encode(password)));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("La contraseña no puede superar 72 bytes UTF-8.");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("El correo electrónico no es válido.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private record StoredAccount(SessionAccount sessionAccount, String passwordHash) { }
}
