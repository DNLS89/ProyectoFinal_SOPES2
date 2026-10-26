package com.sopes.api.config;

import com.sopes.api.model.SharedResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Declara las capacidades compartidas del centro para inyectarlas en los servicios. */
@Configuration
public class ResourceConfiguration implements WebMvcConfigurer {
    private final RoleAccessInterceptor roleAccessInterceptor;

    public ResourceConfiguration(RoleAccessInterceptor roleAccessInterceptor) {
        this.roleAccessInterceptor = roleAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleAccessInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/operator/login", "/customer/login", "/customer/register", "/login", "/logout", "/error", "/favicon.ico");
    }

    @Bean
    public List<SharedResource> sharedResources() {
        return List.of(
                new SharedResource("PACKING", "Estaciones de empaque", 6),
                new SharedResource("LOADING", "Áreas de carga", 2),
                new SharedResource("WAREHOUSE_WORKER", "Encargados de bodega", 10),
                new SharedResource("FORKLIFT", "Montacargas", 3),
                new SharedResource("QUALITY_CONTROL", "Estaciones de control de calidad", 4),
                new SharedResource("BARCODE_SCANNER", "Escáner de códigos compartido", 1)
        );
    }
}
