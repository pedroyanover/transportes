package com.tpi.solicitudes.service;

import com.tpi.solicitudes.dto.*;
import com.tpi.solicitudes.entity.*;
import com.tpi.solicitudes.repository.*;
import com.tpi.solicitudes.client.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SolicitudService {
    
    private final SolicitudRepository solicitudRepository;
    private final ClienteRepository clienteRepository;
    private final ContenedorRepository contenedorRepository;
    private final LogisticaClient logisticaClient;
    private final FacturacionClient facturacionClient;
    
    /**
     * Crea una nueva solicitud de transporte
     * Incluye creación/validación de cliente y contenedor
     */
    public SolicitudDTO crearSolicitud(CrearSolicitudRequest request) {
        try {
            log.info("📋 =========== INICIANDO CREACIÓN DE SOLICITUD ===========");
            log.info("Origen: {} → Destino: {}", request.getOrigenDireccion(), request.getDestinoDireccion());
            
            // 1. Validar/crear cliente
            log.info("1️⃣ Validando/creando cliente...");
            Cliente cliente = obtenerOCrearCliente(request.getCliente());
            log.info("✅ Cliente procesado: ID={}, Email={}", cliente.getId(), cliente.getEmail());
            
            // 2. Crear contenedor (AQUÍ se valida que no esté en uso)
            log.info("2️⃣ Validando/creando contenedor...");
            Contenedor contenedor = crearContenedor(cliente.getId(), request.getContenedor(), 
                                                    request.getOrigenDireccion());
            log.info("✅ Contenedor procesado: ID={}, Identificación={}, Estado={}", 
                    contenedor.getId(), contenedor.getNumeroIdentificacion(), contenedor.getEstado());
            
            // 3. Crear solicitud
            log.info("3️⃣ Creando solicitud...");
            Solicitud solicitud = Solicitud.builder()
                .clienteId(cliente.getId())
                .contenedorId(contenedor.getId())
                .origenDireccion(request.getOrigenDireccion())
                .destinoDireccion(request.getDestinoDireccion())
                .estado("BORRADOR")
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build();
            
            Solicitud guardada = solicitudRepository.save(solicitud);
            log.info("✅ Solicitud creada con ID: {}", guardada.getId());
            
            // Integración con ms-logistica: calcular todas las rutas tentativas según distancias
            log.info("4️⃣ Calculando rutas tentativas...");
            List<RutaDTO> rutasCalculadas = null;
            try {
                rutasCalculadas = logisticaClient.calcularRutasTentativas(
                    guardada.getId(),
                    request.getOrigenDireccion(),
                    request.getDestinoDireccion(),
                    request.getContenedor().getPesoKg(),
                    request.getContenedor().getVolumenM3()
                );
                
                // Si se generaron opciones, utilizar la más económica para el estimado
                if (rutasCalculadas != null && !rutasCalculadas.isEmpty()) {
                    RutaDTO mejorRuta = rutasCalculadas.stream()
                            .min(java.util.Comparator.comparing(RutaDTO::getCostoTotalEstimado))
                            .orElse(rutasCalculadas.get(0));
                    
                    guardada.setCostoEstimado(mejorRuta.getCostoTotalEstimado());
                    guardada.setTiempoEstimadoHoras(mejorRuta.getTiempoEstimadoHoras());
                    guardada = solicitudRepository.save(guardada);
                    
                    log.info("✅ {} rutas calculadas para solicitud (Mejor: {} - {}km - ${} - {}hs)", 
                            rutasCalculadas.size(), 
                            mejorRuta.getEstrategia(), 
                            mejorRuta.getDistanciaTotal(), 
                            mejorRuta.getCostoTotalEstimado(), 
                            mejorRuta.getTiempoEstimadoHoras());
                }
            } catch (Exception e) {
                log.warn("⚠️ No se pudo calcular rutas automáticamente para solicitud {}: {}", 
                        guardada.getId(), e.getMessage());
            }
            
            log.info("📋 =========== SOLICITUD CREADA EXITOSAMENTE ===========");
            return convertirASolicitudDTO(guardada, rutasCalculadas);
            
        } catch (IllegalStateException e) {
            // Es una validación deliberada (contenedor en uso, etc)
            log.error("❌ Validación fallida: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ Error inesperado al crear solicitud: {}", e.getMessage(), e);
            throw new RuntimeException("Error al crear solicitud: " + e.getMessage(), e);
        }
    }
    
    /**
     * Obtiene o crea un cliente según el email
     */
    private Cliente obtenerOCrearCliente(ClienteDTO dto) {
        return clienteRepository.findByEmail(dto.getEmail())
            .orElseGet(() -> {
                log.info("Cliente no existe, creando nuevo con email: {}", dto.getEmail());
                Cliente nuevo = Cliente.builder()
                    .nombreCompleto(dto.getNombreCompleto())
                    .email(dto.getEmail())
                    .telefono(dto.getTelefono())
                    .direccion(dto.getDireccion())
                    .fechaRegistro(LocalDateTime.now())
                    .build();
                return clienteRepository.save(nuevo);
            });
    }
    
    /**
     * Obtiene o crea un contenedor asociado al cliente.
     * - Si el contenedor existe y está EN_ESPERA/EN_TRANSITO → RECHAZAR (ya está asignado)
     * - Si el contenedor existe y está ENTREGADO/CREADO → permitir reasignación
     * - Si no existe → crear uno nuevo
     */
    private Contenedor crearContenedor(Long clienteId, 
                                      CrearSolicitudRequest.ContenedorSimpleDTO dto,
                                      String ubicacionInicial) {
        
        String numeroIdentificacion = dto.getNumeroIdentificacion();
        log.info("🔍 Buscando contenedor con número de identificación: '{}'", numeroIdentificacion);
        
        // Buscar si el contenedor ya existe por número de identificación
        java.util.Optional<Contenedor> contenedorExistente = 
            contenedorRepository.findByNumeroIdentificacion(numeroIdentificacion);
        
        if (contenedorExistente.isPresent()) {
            Contenedor existente = contenedorExistente.get();
            String estadoActual = existente.getEstado();
            
            log.info("✅ Contenedor ENCONTRADO en BD: ID={}, NumId='{}', Estado='{}', ClienteID={}", 
                    existente.getId(), numeroIdentificacion, estadoActual, existente.getClienteId());
            
            // ✅ VALIDACIÓN CRÍTICA: Si el contenedor está en uso (EN_ESPERA o EN_TRANSITO), RECHAZAR
            if ("EN_ESPERA".equals(estadoActual) || "EN_TRANSITO".equals(estadoActual)) {
                String mensaje = String.format(
                    "❌ CONTENEDOR EN USO: '%s' está en estado '%s' (asignado a otra solicitud). " +
                    "No puede reutilizarse hasta que se complete o cancele esa solicitud.",
                    numeroIdentificacion, estadoActual
                );
                log.error(mensaje);
                throw new IllegalStateException(mensaje);
            }
            
            // Si llegamos aquí: estado es CREADO, ENTREGADO, o EN_DEPOSITO → permitir reasignación
            log.info("🔄 Reasignando contenedor '{}' a nueva solicitud (estado anterior: '{}')", 
                    numeroIdentificacion, estadoActual);
            
            existente.setEstado("EN_ESPERA");
            existente.setClienteId(clienteId);
            existente.setUbicacionActual(ubicacionInicial);
            existente.setObservaciones(dto.getObservaciones());
            
            Contenedor actualizado = contenedorRepository.save(existente);
            log.info("✅ Contenedor '{}' actualizado: ID={}, Estado=EN_ESPERA", 
                    numeroIdentificacion, actualizado.getId());
            return actualizado;
        }
        
        // Si no existe, crear uno nuevo
        log.info("📦 Contenedor '{}' NO EXISTS en BD. Creando uno nuevo...", numeroIdentificacion);
        
        Contenedor contenedor = Contenedor.builder()
            .clienteId(clienteId)
            .numeroIdentificacion(numeroIdentificacion)
            .pesoKg(dto.getPesoKg())
            .volumenM3(dto.getVolumenM3())
            .tipo(dto.getTipo() != null ? dto.getTipo() : "ESTANDAR")
            .estado("EN_ESPERA")
            .ubicacionActual(ubicacionInicial)
            .fechaCreacion(LocalDateTime.now())
            .observaciones(dto.getObservaciones())
            .build();
        
        Contenedor guardado = contenedorRepository.save(contenedor);
        log.info("✅ Contenedor CREADO: ID={}, NumId='{}', Estado=EN_ESPERA", 
                guardado.getId(), numeroIdentificacion);
        
        return guardado;
    }
    
    /**
     * Obtiene una solicitud por ID
     */
    @Transactional(readOnly = true)
    public SolicitudDTO obtenerSolicitud(Long id) {
        Solicitud solicitud = solicitudRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + id));
        return convertirASolicitudDTO(solicitud);
    }
    
    /**
     * Confirma una solicitud (transición BORRADOR → PLANIFICADA)
     * Requiere que ya tenga una ruta asignada
     */
    public SolicitudDTO confirmarSolicitud(Long id) {
        log.info("Confirmando solicitud ID: {}", id);
        
        Solicitud solicitud = solicitudRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + id));
        
        if (!"BORRADOR".equals(solicitud.getEstado())) {
            throw new IllegalStateException(
                "Solo se pueden confirmar solicitudes en estado BORRADOR. Estado actual: " + solicitud.getEstado());
        }
        
        // Validar que tenga costo estimado (indica que se calcularon rutas)
        if (solicitud.getCostoEstimado() == null || solicitud.getCostoEstimado() <= 0) {
            throw new IllegalStateException(
                "La solicitud no tiene rutas calculadas. Primero debe asignar una ruta.");
        }
        
        solicitud.setEstado("PLANIFICADA");
        solicitud.setFechaActualizacion(LocalDateTime.now());
        
        Solicitud actualizada = solicitudRepository.save(solicitud);
        log.info("✅ Solicitud {} confirmada. Estado: PLANIFICADA", id);
        
        return convertirASolicitudDTO(actualizada);
    }
    
    /**
     * Actualiza el estado de una solicitud (usado internamente por otros microservicios)
     */
    public void actualizarEstado(Long id, String nuevoEstado) {
        log.info("Actualizando estado de solicitud {} a {}", id, nuevoEstado);
        
        Solicitud solicitud = solicitudRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + id));
        
        solicitud.setEstado(nuevoEstado);
        solicitud.setFechaActualizacion(LocalDateTime.now());
        
        // Si se finaliza (o se marca como entregada), calcular costos reales y generar factura
        if ("FINALIZADA".equals(nuevoEstado) || "ENTREGADA".equals(nuevoEstado)) {
            finalizarSolicitudConCalculos(solicitud);
        }
        
        solicitudRepository.save(solicitud);
        log.info("✅ Estado de solicitud {} actualizado a {}", id, nuevoEstado);
    }
    
    /**
     * Finaliza solicitud calculando costos reales y generando factura
     */
    private void finalizarSolicitudConCalculos(Solicitud solicitud) {
        log.info("🧮 Calculando costos reales para solicitud {}", solicitud.getId());
        
        try {
            // Obtener todos los tramos de la solicitud (para tiempo real y validación)
            List<TramoDTO> tramos = logisticaClient.listarTramosPorSolicitud(solicitud.getId());
            if (tramos.isEmpty()) {
                log.warn("⚠️ No hay tramos para la solicitud {}. Se generará factura igualmente.", solicitud.getId());
            }

            // Calcular tiempo real en horas (solo si hay datos de tramos)
            Double tiempoRealHoras = null;
            if (!tramos.isEmpty()) {
                LocalDateTime primeraFechaInicio = tramos.stream()
                        .filter(t -> t.getFechaInicio() != null)
                        .map(TramoDTO::getFechaInicio)
                        .min(LocalDateTime::compareTo)
                        .orElse(null);

                LocalDateTime ultimaFechaFin = tramos.stream()
                        .filter(t -> t.getFechaFin() != null)
                        .map(TramoDTO::getFechaFin)
                        .max(LocalDateTime::compareTo)
                        .orElse(null);

                if (primeraFechaInicio != null && ultimaFechaFin != null) {
                    long minutos = java.time.Duration.between(primeraFechaInicio, ultimaFechaFin).toMinutes();
                    tiempoRealHoras = minutos / 60.0;
                }
            } else {
                // Si no tenemos datos de tramos, dejamos tiempoReal en 0 para reflejar que no se pudo calcular
                tiempoRealHoras = 0.0;
            }

            // Generar factura y usar su total como costo real
            try {
                FacturaDTO factura = facturacionClient.generarFactura(solicitud.getId());
                Double totalFactura = factura.getTotal();
                solicitud.setCostoReal(totalFactura);
                log.info("📄 Factura generada: {} - Total: ${}", factura.getNumeroFactura(), totalFactura);
            } catch (Exception e) {
                log.warn("⚠️ No se pudo generar factura automáticamente: {}", e.getMessage());
            }

            solicitud.setTiempoRealHoras(tiempoRealHoras);
            solicitud.setFechaEntrega(LocalDateTime.now());

        } catch (Exception e) {
            log.error("❌ Error al calcular costos reales para solicitud {}: {}", 
                    solicitud.getId(), e.getMessage());
        }
    }
    
    /**
     * Lista todas las solicitudes pendientes (PROGRAMADA o EN_TRANSITO)
     */
    @Transactional(readOnly = true)
    public List<SolicitudDTO> listarSolicitudesPendientes() {
        return solicitudRepository.findPendientes()
            .stream()
            .map(this::convertirASolicitudDTO)
            .collect(Collectors.toList());
    }
    
    /**
     * Lista solicitudes por estado
     */
    @Transactional(readOnly = true)
    public List<SolicitudDTO> listarSolicitudesPorEstado(String estado) {
        return solicitudRepository.findByEstado(estado)
            .stream()
            .map(this::convertirASolicitudDTO)
            .collect(Collectors.toList());
    }
    
    /**
     * Lista solicitudes de un cliente
     */
    @Transactional(readOnly = true)
    public List<SolicitudDTO> listarSolicitudesDeCliente(Long clienteId) {
        return solicitudRepository.findByClienteId(clienteId)
            .stream()
            .map(this::convertirASolicitudDTO)
            .collect(Collectors.toList());
    }
    
    /**
     * Actualiza costos y tiempos estimados
     */
    public SolicitudDTO actualizarCostoEstimado(Long id, Double costoEstimado, Double tiempoEstimadoHoras) {
        Solicitud solicitud = solicitudRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + id));
        
        solicitud.setCostoEstimado(costoEstimado);
        solicitud.setTiempoEstimadoHoras(tiempoEstimadoHoras);
        solicitud.setEstado("PROGRAMADA");
        solicitud.setFechaActualizacion(LocalDateTime.now());
        
        Solicitud actualizada = solicitudRepository.save(solicitud);
        log.info("Solicitud ID={} actualizada con costo=${} y tiempo={}hs", 
                id, costoEstimado, tiempoEstimadoHoras);
        
        return convertirASolicitudDTO(actualizada);
    }
    
    /**
     * Finaliza una solicitud registrando costos y tiempos reales
     */
    public SolicitudDTO finalizarSolicitud(Long id, Double costoReal, Double tiempoRealHoras) {
        Solicitud solicitud = solicitudRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + id));
        
        solicitud.setCostoReal(costoReal);
        solicitud.setTiempoRealHoras(tiempoRealHoras);
        solicitud.setEstado("ENTREGADA");
        solicitud.setFechaEntrega(LocalDateTime.now());
        solicitud.setFechaActualizacion(LocalDateTime.now());
        
        // Actualizar estado del contenedor
        Contenedor contenedor = contenedorRepository.findById(solicitud.getContenedorId())
            .orElseThrow(() -> new RuntimeException("Contenedor no encontrado"));
        contenedor.setEstado("ENTREGADO");
        contenedor.setUbicacionActual(solicitud.getDestinoDireccion());
        // Lat/Lon eliminados - solo usamos direcciones
        contenedorRepository.save(contenedor);

        // Generar factura automáticamente al entregar la solicitud
        finalizarSolicitudConCalculos(solicitud);
        
        Solicitud finalizada = solicitudRepository.save(solicitud);
        log.info("Solicitud ID={} finalizada. Costo real=${}, Tiempo real={}hs", 
                id, costoReal, tiempoRealHoras);
        
        return convertirASolicitudDTO(finalizada);
    }
    
    /**
     * Obtiene la factura de una solicitud desde ms-facturacion-v2
     */
    public FacturaDTO obtenerFacturaSolicitud(Long solicitudId) {
        // Verificar que la solicitud existe
        solicitudRepository.findById(solicitudId)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + solicitudId));
        
        try {
            FacturaDTO factura = facturacionClient.obtenerFacturaPorSolicitud(solicitudId);
            log.info("Factura obtenida para solicitud {}: {} - ${}", 
                    solicitudId, factura.getNumeroFactura(), factura.getTotal());
            return factura;
        } catch (Exception e) {
            log.error("Error al obtener factura para solicitud {}: {}", solicitudId, e.getMessage());
            throw new RuntimeException("No se pudo obtener la factura: " + e.getMessage());
        }
    }
    
    /**
     * Lista todas las solicitudes
     */
    public List<SolicitudDTO> listarTodasSolicitudes() {
        log.info("Listando todas las solicitudes");
        return solicitudRepository.findAll().stream()
                .map(this::convertirASolicitudDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Lista todos los clientes
     */
    public List<ClienteDTO> listarTodosClientes() {
        log.info("Listando todos los clientes");
        return clienteRepository.findAll().stream()
                .map(this::convertirAClienteDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Lista todos los contenedores
     */
    public List<ContenedorDTO> listarTodosContenedores() {
        log.info("Listando todos los contenedores");
        return contenedorRepository.findAll().stream()
                .map(this::convertirAContenedorDTO)
                .collect(Collectors.toList());
    }
    
    // Métodos de conversión
    private SolicitudDTO convertirASolicitudDTO(Solicitud s) {
        return convertirASolicitudDTO(s, null);
    }
    
    private SolicitudDTO convertirASolicitudDTO(Solicitud s, List<RutaDTO> rutasTentativas) {
        // Obtener datos del contenedor si existe
        ContenedorDTO contenedorDTO = null;
        if (s.getContenedorId() != null) {
            Contenedor contenedor = contenedorRepository.findById(s.getContenedorId()).orElse(null);
            if (contenedor != null) {
                contenedorDTO = convertirAContenedorDTO(contenedor);
            }
        }
        
        return SolicitudDTO.builder()
            .id(s.getId())
            .clienteId(s.getClienteId())
            .contenedorId(s.getContenedorId())
            .origenDireccion(s.getOrigenDireccion())
            .destinoDireccion(s.getDestinoDireccion())
            .estado(s.getEstado())
            .costoEstimado(s.getCostoEstimado())
            .tiempoEstimadoHoras(s.getTiempoEstimadoHoras())
            .costoReal(s.getCostoReal())
            .tiempoRealHoras(s.getTiempoRealHoras())
            .fechaCreacion(s.getFechaCreacion())
            .fechaActualizacion(s.getFechaActualizacion())
            .fechaEntrega(s.getFechaEntrega())
            .contenedor(contenedorDTO)
            .rutasTentativas(rutasTentativas)
            .build();
    }
    
    private ClienteDTO convertirAClienteDTO(Cliente c) {
        return ClienteDTO.builder()
            .id(c.getId())
            .nombreCompleto(c.getNombreCompleto())
            .email(c.getEmail())
            .telefono(c.getTelefono())
            .direccion(c.getDireccion())
            .build();
    }
    
    private ContenedorDTO convertirAContenedorDTO(Contenedor c) {
        return ContenedorDTO.builder()
            .id(c.getId())
            .numeroIdentificacion(c.getNumeroIdentificacion())
            .clienteId(c.getClienteId())
            .pesoKg(c.getPesoKg())
            .volumenM3(c.getVolumenM3())
            .tipo(c.getTipo())
            .estado(c.getEstado())
            .ubicacionActual(c.getUbicacionActual())
            .latActual(c.getLatActual())
            .lonActual(c.getLonActual())
            .observaciones(c.getObservaciones())
            .build();
    }
    
    /**
     * Obtiene todas las rutas calculadas para una solicitud
     */
    public List<RutaDTO> obtenerRutasSolicitud(Long solicitudId) {
        log.info("Obteniendo rutas para solicitud {}", solicitudId);
        
        // Verificar que la solicitud existe
        Solicitud solicitud = solicitudRepository.findById(solicitudId)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + solicitudId));
        
        // Obtener rutas desde ms-logistica
        try {
            return logisticaClient.listarRutasPorSolicitud(solicitudId);
        } catch (Exception e) {
            log.error("Error al obtener rutas de solicitud {}: {}", solicitudId, e.getMessage());
            throw new RuntimeException("No se pudieron obtener las rutas de la solicitud", e);
        }
    }
    
    /**
     * Finaliza una solicitud y libera todos los recursos asociados
     */
    public SolicitudDTO finalizarSolicitud(Long solicitudId) {
        log.info("📋 Finalizando solicitud {} y liberando recursos", solicitudId);
        
        Solicitud solicitud = solicitudRepository.findById(solicitudId)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + solicitudId));
        
        // ✅ Liberar contenedor
        if (solicitud.getContenedorId() != null) {
            Contenedor contenedor = contenedorRepository.findById(solicitud.getContenedorId()).orElse(null);
            if (contenedor != null) {
                contenedor.setEstado("ENTREGADO"); // O DISPONIBLE según tu lógica
                contenedorRepository.save(contenedor);
                log.info("✅ Contenedor {} liberado a estado ENTREGADO", contenedor.getId());
            }
        }
        
        // Marcar solicitud como finalizada
        solicitud.setEstado("COMPLETADA");
        solicitud.setFechaEntrega(LocalDateTime.now());
        Solicitud finalizada = solicitudRepository.save(solicitud);
        
        log.info("✅ Solicitud {} finalizada. Recursos liberados.", solicitudId);
        return convertirASolicitudDTO(finalizada);
    }
    
    /**
     * Cancela una solicitud y libera todos los recursos asociados
     */
    public SolicitudDTO cancelarSolicitud(Long solicitudId, String razon) {
        log.info("❌ Cancelando solicitud {} - Razón: {}", solicitudId, razon);
        
        Solicitud solicitud = solicitudRepository.findById(solicitudId)
            .orElseThrow(() -> new RuntimeException("Solicitud no encontrada con ID: " + solicitudId));
        
        // ✅ Liberar contenedor
        if (solicitud.getContenedorId() != null) {
            Contenedor contenedor = contenedorRepository.findById(solicitud.getContenedorId()).orElse(null);
            if (contenedor != null) {
                contenedor.setEstado("CREADO"); // Volver a estado disponible
                contenedorRepository.save(contenedor);
                log.info("✅ Contenedor {} liberado a estado CREADO", contenedor.getId());
            }
        }
        
        // Marcar solicitud como cancelada
        solicitud.setEstado("CANCELADA");
        Solicitud cancelada = solicitudRepository.save(solicitud);
        
        log.info("✅ Solicitud {} cancelada. Recursos liberados.", solicitudId);
        return convertirASolicitudDTO(cancelada);
    }
}

