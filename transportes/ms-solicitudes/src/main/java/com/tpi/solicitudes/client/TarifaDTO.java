package com.tpi.solicitudes.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TarifaDTO {
    private Long id;
    private String descripcion;
    private Double cargoGestionBase;
    private Double cargoGestionPorTramo;
    private Double precioCombustibleLitro;
    private Double factorEstadiaDia;
    private LocalDate fechaVigenciaDesde;
    private LocalDate fechaVigenciaHasta;
    private String estado;
}
