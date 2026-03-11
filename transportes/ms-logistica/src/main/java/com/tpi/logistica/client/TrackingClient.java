package com.tpi.logistica.client;

import com.tpi.logistica.dto.RegistrarEventoRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "MS-TRACKING")
public interface TrackingClient {

    @PostMapping("/api/eventos/registrar")
    void registrarEvento(RegistrarEventoRequest request);
}
