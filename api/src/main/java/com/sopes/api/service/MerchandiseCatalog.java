package com.sopes.api.service;

import com.sopes.api.model.MerchandiseType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchandiseCatalog {
    private final List<MerchandiseType> types = List.of(
            new MerchandiseType("FRAGILE", "Frágil", "Requiere cuidado y revisión de calidad.", List.of("PACKING", "QUALITY_CONTROL")),
            new MerchandiseType("HEAVY", "Pesada", "Necesita montacargas y personal de bodega.", List.of("FORKLIFT", "WAREHOUSE_WORKER")),
            new MerchandiseType("HAZARDOUS", "Regulada", "Manejo supervisado y control de calidad.", List.of("WAREHOUSE_WORKER", "QUALITY_CONTROL")),
            new MerchandiseType("ELECTRONICS", "Electrónica", "Validación con escáner y empaque protegido.", List.of("BARCODE_SCANNER", "PACKING")),
            // Estos dos flujos comparten montacargas y personal, permitiendo demostrar contención.
            new MerchandiseType("OVERSIZED", "Voluminosa", "Requiere traslado y manipulación coordinados.", List.of("FORKLIFT", "WAREHOUSE_WORKER", "LOADING")),
            new MerchandiseType("PALLETIZED", "Paletizada", "Se mueve con montacargas y personal de bodega.", List.of("FORKLIFT", "WAREHOUSE_WORKER", "PACKING"))
    );

    public List<MerchandiseType> findAll() { return types; }

    public MerchandiseType findByCode(String code) {
        return types.stream().filter(type -> type.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tipo de mercancía desconocido."));
    }
}
