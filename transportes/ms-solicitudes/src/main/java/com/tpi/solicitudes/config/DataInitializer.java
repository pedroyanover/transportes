package com.tpi.solicitudes.config;

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
        Integer clientesCount = null;
        try {
            clientesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM v2_clientes", Integer.class);
        } catch (DataAccessException ex) {
            System.out.println("⚠️ Tabla v2_clientes no disponible todavía, omitiendo carga inicial de Solicitudes");
        }

        if (clientesCount != null && clientesCount == 0) {
            System.out.println("🔄 Cargando datos iniciales de Solicitudes...");
            
            // Clientes
            jdbcTemplate.execute("""
                INSERT INTO v2_clientes (nombre_completo, email, telefono, direccion, fecha_registro)
                VALUES 
                    ('Juan Pérez', 'juan.perez@email.com', '+5493511234567', 'Av. Colón 1234, Córdoba', CURRENT_TIMESTAMP),
                    ('María González', 'maria.gonzalez@email.com', '+5493517654321', 'Av. Vélez Sársfield 5678, Córdoba', CURRENT_TIMESTAMP),
                    ('Carlos Rodríguez', 'carlos.rodriguez@email.com', '+5493519876543', 'Av. Rafael Núñez 910, Córdoba', CURRENT_TIMESTAMP),
                    ('Ana Martínez', 'ana.martinez@email.com', '+5491155554444', 'Av. Corrientes 1234, Buenos Aires', CURRENT_TIMESTAMP),
                    ('Pedro López', 'pedro.lopez@email.com', '+5493415556666', 'San Martín 567, Rosario', CURRENT_TIMESTAMP)
                ON CONFLICT (email) DO NOTHING
            """);
            
            // Contenedores
            jdbcTemplate.execute("""
                INSERT INTO v2_contenedores (cliente_id, numero_identificacion, peso_kg, volumen_m3, tipo, estado, fecha_creacion)
                VALUES 
                    (1, 'CONT001', 12000.0, 28.0, 'DRY', 'DISPONIBLE', CURRENT_TIMESTAMP),
                    (2, 'CONT002', 8000.0, 20.0, 'REEFER', 'DISPONIBLE', CURRENT_TIMESTAMP),
                    (3, 'CONT003', 15000.0, 33.0, 'DRY', 'DISPONIBLE', CURRENT_TIMESTAMP),
                    (1, 'CONT004', 6000.0, 18.0, 'DRY', 'DISPONIBLE', CURRENT_TIMESTAMP),
                    (2, 'CONT005', 10000.0, 25.0, 'REEFER', 'DISPONIBLE', CURRENT_TIMESTAMP)
                ON CONFLICT (numero_identificacion) DO NOTHING
            """);
            
            // Solicitudes
            jdbcTemplate.execute("""
                INSERT INTO v2_solicitudes (cliente_id, contenedor_id, origen_direccion, destino_direccion, estado, fecha_creacion, fecha_actualizacion)
                VALUES 
                    (1, 1, 'Buenos Aires, Argentina', 'Rosario, Argentina', 'BORRADOR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                    (2, 2, 'Rosario, Argentina', 'Córdoba, Argentina', 'BORRADOR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);
            
            System.out.println("✅ Datos iniciales de Solicitudes cargados: 5 clientes, 5 contenedores, 2 solicitudes");
        } else {
            System.out.println("ℹ️ Datos de Solicitudes ya existen, omitiendo carga inicial");
        }
    }
}
