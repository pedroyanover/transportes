package com.tpi.facturacion.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        // Verificar si ya hay datos (protegido contra tablas aún no creadas)
        Integer tarifasCount = null;
        try {
            tarifasCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM v2_tarifas", Integer.class);
        } catch (DataAccessException ex) {
            System.out.println("⚠️ Tabla v2_tarifas no disponible todavía, omitiendo carga inicial de Facturación");
        }

        if (tarifasCount != null && tarifasCount == 0) {
            System.out.println("🔄 Cargando datos iniciales de Facturación...");
            
            // Tarifa vigente
            jdbcTemplate.execute("""
                INSERT INTO v2_tarifas (descripcion, cargo_gestion_base, cargo_gestion_por_tramo, precio_combustible_litro, factor_estadia_dia, fecha_vigencia_desde, fecha_vigencia_hasta, estado, fecha_creacion)
                VALUES 
                    ('Tarifa Estándar 2025', 5000.0, 2000.0, 850.0, 1.0, '2025-01-01', NULL, 'ACTIVA', CURRENT_TIMESTAMP)
            """);
            
            System.out.println("✅ Datos iniciales de Facturación cargados: 1 tarifa activa");
        } else {
            System.out.println("ℹ️ Datos de Facturación ya existen, omitiendo carga inicial");
        }
    }
}
