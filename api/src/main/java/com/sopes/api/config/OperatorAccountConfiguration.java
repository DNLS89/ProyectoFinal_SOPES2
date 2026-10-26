package com.sopes.api.config;

import com.sopes.api.service.PortalAccountService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Crea la cuenta inicial de operador desde variables de entorno antes de aceptar peticiones. */
@Configuration
public class OperatorAccountConfiguration {
    @Bean
    public ApplicationRunner provisionOperatorAccount(
            PortalAccountService accounts,
            @Value("${redxela.operator.email:}") String email,
            @Value("${redxela.operator.password:}") String password) {
        return args -> {
            if (email.isBlank() || password.isBlank()) {
                LoggerFactory.getLogger(OperatorAccountConfiguration.class)
                        .warn("No se configuró operador inicial. Defina REDXELA_OPERATOR_EMAIL y REDXELA_OPERATOR_PASSWORD.");
                return;
            }
            accounts.provisionOperator(email, password);
        };
    }
}
