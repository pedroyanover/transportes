package com.tpi.logistica.client;

import com.tpi.logistica.dto.EstadiaRequestDTO;
import com.tpi.logistica.dto.EstadiaResponseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "MS-FACTURACION-V2")
public interface FacturacionClient {
    
    /**
     * Obtiene la tarifa vigente actual
     */
    @GetMapping("/api/tarifas/vigente")
    TarifaDTO obtenerTarifaVigente();
    
    /**
     * Genera automáticamente una factura para una solicitud
     * consultando los tramos desde ms-logistica
     */
    @PostMapping("/api/facturas/generar")
    FacturaDTO generarFactura(@RequestParam Long solicitudId);
    
    /**
     * Registra la entrada de un contenedor a un depósito
     */
    @PostMapping("/api/estadias/registrar-entrada")
    EstadiaResponseDTO registrarEntradaDeposito(@RequestBody EstadiaRequestDTO request);
    
    /**
     * Registra la salida de un contenedor de un depósito
     */
    @PostMapping("/api/estadias/{id}/registrar-salida")
    EstadiaResponseDTO registrarSalidaDeposito(@PathVariable Long id);
}
