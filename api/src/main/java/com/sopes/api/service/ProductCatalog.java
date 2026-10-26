package com.sopes.api.service;

import com.sopes.api.model.Product;
import org.springframework.stereotype.Service;

import java.util.List;

/** Mantiene el catálogo fijo de productos asociado a sus tipos de mercancía. */
@Service
public class ProductCatalog {
    private final MerchandiseCatalog merchandise;
    private final List<Product> products;

    public ProductCatalog(MerchandiseCatalog merchandise) {
        this.merchandise = merchandise;
        products = List.of(
                new Product("SKU-001", "Vaso de vidrio", merchandise.findByCode("FRAGILE")),
                new Product("SKU-002", "Equipo de gimnasio", merchandise.findByCode("HEAVY")),
                new Product("SKU-003", "Limpiador industrial", merchandise.findByCode("HAZARDOUS")),
                new Product("SKU-004", "Audífonos inalámbricos", merchandise.findByCode("ELECTRONICS")),
                new Product("SKU-005", "Mueble modular", merchandise.findByCode("OVERSIZED")),
                new Product("SKU-006", "Caja de alimentos", merchandise.findByCode("PALLETIZED"))
        );
    }

    public List<Product> findAll() { return products; }

    public Product findByCode(String code) {
        return products.stream().filter(product -> product.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Producto desconocido."));
    }
}
