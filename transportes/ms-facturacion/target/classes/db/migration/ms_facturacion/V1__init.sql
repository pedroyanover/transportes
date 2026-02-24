-- V1 Init migration for ms_facturacion
-- Migrated from src/main/resources/schema.sql

-- Tabla: Tarifas
CREATE TABLE IF NOT EXISTS v2_tarifas (
    id BIGSERIAL PRIMARY KEY,
    descripcion VARCHAR(255) NOT NULL,
    cargo_gestion_base DOUBLE PRECISION NOT NULL,
    cargo_gestion_por_tramo DOUBLE PRECISION NOT NULL,
    precio_combustible_litro DOUBLE PRECISION NOT NULL,
    factor_estadia_dia DOUBLE PRECISION NOT NULL,
    fecha_vigencia_desde DATE NOT NULL,
    fecha_vigencia_hasta DATE,
    estado VARCHAR(50) NOT NULL DEFAULT 'ACTIVA',
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_vigencia CHECK (fecha_vigencia_hasta IS NULL OR fecha_vigencia_hasta >= fecha_vigencia_desde)
);

-- Tabla: Facturas
CREATE TABLE IF NOT EXISTS v2_facturas (
    id BIGSERIAL PRIMARY KEY,
    solicitud_id BIGINT NOT NULL,
    tarifa_id BIGINT NOT NULL,
    numero_factura VARCHAR(50) UNIQUE NOT NULL,
    cargo_gestion DOUBLE PRECISION NOT NULL,
    costo_transporte DOUBLE PRECISION NOT NULL,
    costo_combustible DOUBLE PRECISION NOT NULL,
    costo_estadias DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    subtotal DOUBLE PRECISION NOT NULL,
    impuestos DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    total DOUBLE PRECISION NOT NULL,
    estado VARCHAR(50) NOT NULL DEFAULT 'PENDIENTE',
    fecha_emision TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_vencimiento DATE NOT NULL,
    fecha_pago TIMESTAMP,
    observaciones TEXT,
    CONSTRAINT fk_factura_tarifa FOREIGN KEY (tarifa_id)
        REFERENCES v2_tarifas(id) ON DELETE RESTRICT
);

-- Tabla: Estadías en Depósito
CREATE TABLE IF NOT EXISTS v2_estadias_deposito (
    id BIGSERIAL PRIMARY KEY,
    contenedor_id BIGINT NOT NULL,
    deposito_id BIGINT NOT NULL,
    fecha_entrada TIMESTAMP NOT NULL,
    fecha_salida TIMESTAMP,
    dias_estadia INTEGER,
    costo_dia DOUBLE PRECISION NOT NULL,
    costo_total DOUBLE PRECISION,
    estado VARCHAR(50) NOT NULL DEFAULT 'EN_CURSO',
    observaciones TEXT,
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_actualizacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_v2_tarifas_vigencia ON v2_tarifas(fecha_vigencia_desde, fecha_vigencia_hasta, estado);
CREATE INDEX IF NOT EXISTS idx_v2_facturas_solicitud ON v2_facturas(solicitud_id);
CREATE INDEX IF NOT EXISTS idx_v2_facturas_estado ON v2_facturas(estado);
CREATE INDEX IF NOT EXISTS idx_v2_facturas_numero ON v2_facturas(numero_factura);
CREATE INDEX IF NOT EXISTS idx_v2_estadias_contenedor ON v2_estadias_deposito(contenedor_id);
CREATE INDEX IF NOT EXISTS idx_v2_estadias_deposito ON v2_estadias_deposito(deposito_id);
CREATE INDEX IF NOT EXISTS idx_v2_estadias_estado ON v2_estadias_deposito(estado);
