package com.sopes.api.config;

import com.sopes.api.model.SharedResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ResourceConfiguration {

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
