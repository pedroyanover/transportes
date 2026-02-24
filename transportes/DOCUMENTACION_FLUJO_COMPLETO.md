
### Responsabilidades por Microservicio

| Microservicio | Puerto | Responsabilidad |
|--------------|--------|-----------------|
| **ms-solicitudes-v2** | 8081 | Gestión de solicitudes, contenedores y clientes |
| **ms-logistica** | 8082 | Rutas, tramos, camiones, depósitos, transportistas |
| **ms-tracking-v2** | 8083 | Seguimiento de contenedores en tiempo real |
| **ms-facturacion-v2** | 8084 | Tarifas, facturas y estadías en depósitos |

---

## 🔀 Estados y Transiciones

### 1. Estados de SOLICITUD

```
┌──────────┐  confirmar()   ┌────────────┐  iniciarTramo(1er)  ┌─────────────┐
│ BORRADOR │ ────────────→  │ PLANIFICADA│ ──────────────────→ │ EN_TRANSITO │
└──────────┘                └────────────┘                     └─────────────┘
                                                                        │
                                                   finalizarTramo(último)
                                                                        ↓
                                                                 ┌──────────┐
                                                                 │ENTREGADA │
                                                                 └──────────┘
```

**Transiciones:**
- **BORRADOR → PLANIFICADA**: `confirmarSolicitud(id)` - Requiere ruta asignada
- **PLANIFICADA → EN_TRANSITO**: `iniciarTramo(primerTramo)` - Automático al iniciar 1er tramo
- **EN_TRANSITO → ENTREGADA**: `finalizarTramo(ultimoTramo)` - Automático al finalizar todos los tramos

**Validaciones:**
- No se puede confirmar sin ruta calculada
- No se puede iniciar sin camión asignado
- No se puede finalizar si no están todos los tramos completados

---

### 2. Estados de CONTENEDOR

```
┌──────────┐  crearSolicitud()  ┌────────────┐  iniciarTramo()  ┌───────────┐
DISPONIBLE │ ─────────────────→ │ EN_ESPERA  │ ───────────────→ │EN_TRANSITO│
└──────────┘                    └────────────┘                  └───────────┘
                                                                        │
                                                  finalizarUltimoTramo()
                                                                        ↓
                                     ┌────────────┐  salirDeposito()  ┌──────────┐
                                     │EN_DEPOSITO │ ←───────────────  │ENTREGADO │
                                     └────────────┘  (si aplica)      └──────────┘
```

**Transiciones:**
- **PENDIENTE → EN_ESPERA**: Al crear solicitud
- **EN_ESPERA → EN_TRANSITO**: Al iniciar primer tramo
- **EN_TRANSITO → EN_DEPOSITO**: Al finalizar tramo en depósito
- **EN_DEPOSITO → EN_TRANSITO**: Al iniciar tramo desde depósito
- **EN_TRANSITO → ENTREGADO**: Al finalizar último tramo

---

### 3. Estados de TRAMO

```
┌──────────┐  crearTramos()  ┌──────────┐  asignarCamion()  ┌──────────┐
│ ESTIMADO │ ──────────────→ │ ESTIMADO │ ────────────────→ │ ASIGNADO │
└──────────┘                 └──────────┘                   └──────────┘
                                                                    │
                                                         iniciarTramo()
                                                                    ↓
                            ┌────────────┐  finalizarTramo()  ┌──────────┐                          
                            │ FINALIZADO │ ←───────────────── │ INICIADO │                          
                            └────────────┘                    └──────────┘                          
```

**Transiciones:**
- **ESTIMADO → ASIGNADO**: `asignarCamion(tramoId, camionId, transportistaId)`
- **ASIGNADO → INICIADO**: `iniciarTramo(tramoId)` - Solo por transportista
- **INICIADO → FINALIZADO**: `finalizarTramo(tramoId)` - Solo por transportista

**Validaciones:**
- Solo ASIGNADO puede pasar a INICIADO
- Solo INICIADO puede pasar a FINALIZADO
- No se puede asignar si camión/transportista no disponible

---

### 4. Estados de CAMIÓN

```
┌────────────┐  asignarCamion()  ┌──────────┐  iniciarTramo()  ┌────────┐
│ DISPONIBLE │ ────────────────→ │ ASIGNADO │ ───────────────→ │ EN_USO │
└────────────┘                   └──────────┘                  └────────┘
      ↑                                                               │
      │                                              finalizarTramo() │
      └───────────────────────────────────────────────────────────────┘
```

**Transiciones:**
- **DISPONIBLE → ASIGNADO**: Al asignar a un tramo
- **ASIGNADO → EN_USO**: Al iniciar tramo
- **EN_USO → DISPONIBLE**: Al finalizar tramo (automático)

**Atributos Actualizados:**
- `ubicacionActual`: Se actualiza al finalizar tramo (= destino del tramo)

---

### 5. Estados de TRANSPORTISTA

```
┌────────────┐  asignarCamion()  ┌────────┐
│ DISPONIBLE │ ────────────────→ │ EN_USO │
└────────────┘                   └────────┘
      ↑                               │
      │          finalizarTramo()     │
      └───────────────────────────────┘
```

**Transiciones:**
- **DISPONIBLE → EN_USO**: Al asignar a un tramo
- **EN_USO → DISPONIBLE**: Al finalizar tramo (automático)

---

### 6. Estados de ESTADÍA

```
┌──────────┐  finalizarTramo(destino=DEPOSITO)  ┌──────────┐
│   N/A    │ ──────────────────────────────────→│ EN_CURSO │
└──────────┘                                    └──────────┘
                                                       │
                                    iniciarTramo(origen=DEPOSITO)
                                                       ↓
                                                 ┌────────────┐
                                                 │ FINALIZADA │
                                                 └────────────┘
```

**Transiciones:**
- **N/A → EN_CURSO**: Automático al finalizar tramo con destino=DEPOSITO
- **EN_CURSO → FINALIZADA**: Automático al iniciar tramo con origen=DEPOSITO

**Cálculo automático:**
```java
dias = ChronoUnit.DAYS.between(fechaEntrada, fechaSalida);
if (dias < 1) dias = 1; // Mínimo 1 día
costoTotal = dias × costoDia;
```

---

### 7. Estados de FACTURA

```
┌─────┐  generarFactura()  ┌──────────┐
│ N/A │ ─────────────────→ │ GENERADA │
└─────┘                    └──────────┘
```

**Trigger:**
- Se genera automáticamente al finalizar el último tramo
- Estado único: `GENERADA`

---

### 1. Cálculo de Distancias (Google Maps API)

```java
// Llamada a Google Maps Directions API
Request request = new Request.Builder()
    .url("https://maps.googleapis.com/maps/api/directions/json?" +
         "origin=" + origenLat + "," + origenLon +
         "&destination=" + destinoLat + "," + destinoLon +
         "&key=" + API_KEY)
    .build();

Response response = httpClient.newCall(request).execute();
JsonNode root = objectMapper.readTree(response.body().string());

// Extraer distancia en metros
int distanciaMetros = root.get("routes").get(0)
                          .get("legs").get(0)
                          .get("distance").get("value").asInt();

Double distanciaKm = distanciaMetros / 1000.0;
```

---

### 2. Cálculo de Tiempo Estimado

```java
// Velocidad promedio: 80 km/h
Double tiempoEstimadoHoras = distanciaKm / 80.0;
```

---

### 3. Cálculo de Costo Estimado (al crear ruta)

```java
Tarifa tarifa = obtenerTarifaVigente();

// 1. Cargo de Gestión
Double cargoGestion = tarifa.getCargoGestionBase() + 
                     (cantidadTramos × tarifa.getCargoGestionPorTramo());

// 2. Costo de Transporte (estimado con tarifa base)
Double costoTransporte = distanciaTotalKm × tarifa.getCostoBaseKm();

// 3. Costo de Combustible (estimado con consumo promedio)
Double consumoPromedio = 0.08; // L/km promedio
Double costoCombustible = distanciaTotalKm × consumoPromedio × 
                         tarifa.getPrecioCombustibleLitro();

// 4. Costo Estadías (estimado en 0 en fase de planificación)
Double costoEstadias = 0.0;

// TOTAL ESTIMADO
Double costoEstimado = cargoGestion + costoTransporte + costoCombustible;
```

---

### 4. Cálculo de Costo Real (al generar factura)

```java
Tarifa tarifa = obtenerTarifaVigente();
List<Tramo> tramos = obtenerTramosPorSolicitud(solicitudId);
Solicitud solicitud = obtenerSolicitud(solicitudId);

// 1. Cargo de Gestión (basado en cantidad real de tramos)
Double cargoGestion = tarifa.getCargoGestionBase() + 
                     (tramos.size() × tarifa.getCargoGestionPorTramo());

// 2. Costo de Transporte REAL (usando costo/km de cada camión)
Double costoTransporte = tramos.stream()
    .mapToDouble(tramo -> {
        Camion camion = obtenerCamion(tramo.getCamionId());
        return tramo.getDistanciaKm() × camion.getCostoKm();
    })
    .sum();

// 3. Costo de Combustible REAL (usando consumo de cada camión)
Double costoCombustible = tramos.stream()
    .mapToDouble(tramo -> {
        Camion camion = obtenerCamion(tramo.getCamionId());
        return tramo.getDistanciaKm() × 
               camion.getConsumoLtKm() × 
               tarifa.getPrecioCombustibleLitro();
    })
    .sum();

// 4. Costo de Estadías REAL (estadías finalizadas del contenedor)
Long contenedorId = solicitud.getContenedorId();
List<EstadiaDeposito> estadias = 
    estadiaDepositoRepository.findByContenedorIdAndEstado(contenedorId, "FINALIZADA");

Double costoEstadias = estadias.stream()
    .mapToDouble(EstadiaDeposito::getCostoTotal)
    .sum();

// SUBTOTAL
Double subtotal = cargoGestion + costoTransporte + costoCombustible + costoEstadias;

// IMPUESTOS (21% IVA)
Double impuestos = subtotal × 0.21;

// TOTAL REAL
Double total = subtotal + impuestos;
```

**Ejemplo Numérico:**
```
Cargo Gestión:      $50,000 (base) + 2 tramos × $10,000 = $70,000
Costo Transporte:   700km × $15,000/km = $10,500,000
Costo Combustible:  700km × 0.08L/km × $1,200/L = $67,200
Costo Estadías:     3 días × $50,000/día = $150,000
────────────────────────────────────────────────────────
Subtotal:           $10,787,200
Impuestos (21%):    $2,265,312
────────────────────────────────────────────────────────
TOTAL:              $13,052,512
```

---

### 5. Cálculo de Estadía

```java
// Al SALIR del depósito (iniciar tramo con origen=DEPOSITO)
LocalDateTime entrada = estadia.getFechaEntrada();
LocalDateTime salida = LocalDateTime.now();

long dias = ChronoUnit.DAYS.between(entrada, salida);
if (dias < 1) dias = 1; // Mínimo 1 día

Double costoTotal = dias × estadia.getCostoDia();

estadia.setFechaSalida(salida);
estadia.setDiasEstadia((int) dias);
estadia.setCostoTotal(costoTotal);
estadia.setEstado("FINALIZADA");
```

**Ejemplo:**
```
Entrada:     2025-11-20 14:30
Salida:      2025-11-23 09:15
Días:        3 días
Costo/día:   $50,000
────────────────────────────
Costo Total: $150,000
```

---

### 6. Cálculo de Tiempo Real

```java
// Al finalizar todos los tramos
Double tiempoRealHoras = tramos.stream()
    .mapToDouble(tramo -> {
        if (tramo.getFechaInicio() != null && tramo.getFechaFin() != null) {
            Duration duracion = Duration.between(
                tramo.getFechaInicio(), 
                tramo.getFechaFin()
            );
            return duracion.toMinutes() / 60.0;
        }
        return 0.0;
    })
    .sum();
```

---

## 🔗 Integración entre Microservicios

### Comunicación Síncrona (Feign Clients)

#### ms-logistica → ms-solicitudes-v2

```java
@FeignClient(name = "MS-SOLICITUDES-V2")
public interface SolicitudClient {
    @GetMapping("/api/solicitudes/{id}")
    SolicitudDTO obtenerSolicitud(@PathVariable Long id);
    
    @PatchMapping("/api/solicitudes/{id}/estado")
    void actualizarEstado(@PathVariable Long id, @RequestParam String estado);
    
    @PatchMapping("/api/solicitudes/{id}/finalizar")
    void finalizarSolicitud(@PathVariable Long id, 
                           @RequestParam Double costoReal,
                           @RequestParam Double tiempoReal);
}
```

**Casos de uso:**
- Obtener datos del contenedor al asignar camión
- Actualizar estado a `EN_TRANSITO` al iniciar primer tramo
- Finalizar solicitud al completar todos los tramos

---

#### ms-logistica → ms-facturacion-v2

```java
@FeignClient(name = "MS-FACTURACION-V2")
public interface FacturacionClient {
    @PostMapping("/api/facturas/generar")
    FacturaDTO generarFactura(@RequestParam Long solicitudId);
    
    @PostMapping("/api/estadias/registrar-entrada")
    EstadiaResponseDTO registrarEntradaDeposito(@RequestBody EstadiaRequestDTO request);
    
    @PostMapping("/api/estadias/{id}/registrar-salida")
    EstadiaResponseDTO registrarSalidaDeposito(@PathVariable Long id);
}
```

**Casos de uso:**
- Generar factura automáticamente al finalizar último tramo
- Registrar entrada a depósito al finalizar tramo
- Registrar salida de depósito al iniciar tramo

---

#### ms-facturacion-v2 → ms-logistica

```java
@FeignClient(name = "MS-LOGISTICA")
public interface LogisticaClient {
    @GetMapping("/api/tramos/solicitud/{solicitudId}")
    List<TramoDTO> obtenerTramosPorSolicitud(@PathVariable Long solicitudId);
}
```

**Casos de uso:**
- Obtener tramos finalizados para calcular costo real

---

#### ms-facturacion-v2 → ms-solicitudes-v2

```java
@FeignClient(name = "MS-SOLICITUDES-V2")
public interface SolicitudClient {
    @GetMapping("/api/solicitudes/{id}")
    SolicitudDTO obtenerSolicitud(@PathVariable Long id);
}
```

**Casos de uso:**
- Obtener contenedorId para buscar estadías

---

### Eventos Automáticos en el Sistema

| Evento | Trigger | Acción Automática |
|--------|---------|-------------------|
| **Iniciar Primer Tramo** | `iniciarTramo(id)` | Solicitud → `EN_TRANSITO` |
| **Finalizar Tramo en Depósito** | `finalizarTramo(id)` con `destinoTipo=DEPOSITO` | Crear Estadía con estado `EN_CURSO` |
| **Iniciar Tramo desde Depósito** | `iniciarTramo(id)` con `origenTipo=DEPOSITO` | Finalizar Estadía, calcular costo |
| **Finalizar Último Tramo** | `finalizarTramo(id)` | 1. Solicitud → `ENTREGADA` <br> 2. Contenedor → `ENTREGADO` <br> 3. Generar Factura |
| **Finalizar Tramo** | `finalizarTramo(id)` | Camión → `DISPONIBLE` <br> Transportista → `DISPONIBLE` |

---

## 🎯 Validaciones y Reglas de Negocio

### Validaciones al Asignar Camión

```java
✓ Camión debe existir
✓ Transportista debe existir
✓ Camión debe estar DISPONIBLE
✓ Transportista debe estar DISPONIBLE
✓ Camión debe soportar peso del contenedor
✓ Camión debe soportar volumen del contenedor
✓ Tramo debe estar en estado ESTIMADO
```

### Validaciones al Iniciar Tramo

```java
✓ Tramo debe existir
✓ Tramo debe tener camión asignado
✓ Tramo debe tener transportista asignado
✓ Tramo debe estar en estado ASIGNADO
```

### Validaciones al Finalizar Tramo

```java
✓ Tramo debe existir
✓ Tramo debe estar en estado INICIADO
```

### Validaciones al Confirmar Solicitud

```java
✓ Solicitud debe estar en estado BORRADOR
✓ Solicitud debe tener ruta calculada (costoEstimado > 0)
```

### Validaciones al Generar Factura

```java
✓ Solicitud debe existir
✓ Todos los tramos deben estar FINALIZADOS
✓ No debe existir factura previa para la solicitud
✓ Debe existir tarifa vigente
```

---

## 📊 Tracking y Seguimiento

### Endpoint de Tracking

```
GET /api/tracking/contenedor/{codigoContenedor}
```

**Response:**
```json
{
  "contenedor": {
    "codigo": "CONT-20251124-0001",
    "estado": "EN_TRANSITO",
    "pesoKg": 2500.0,
    "volumenM3": 15.0
  },
  "solicitud": {
    "id": 1,
    "estado": "EN_TRANSITO",
    "origenDireccion": "Av. Corrientes 1000, CABA",
    "destinoDireccion": "Ruta 9 km 200, Rosario",
    "costoEstimado": 9500000.0,
    "tiempoEstimadoHoras": 8.75
  },
  "tramoActual": {
    "id": 1,
    "estado": "INICIADO",
    "origenDireccion": "Av. Corrientes 1000, CABA",
    "destinoDireccion": "Ruta 9 km 200, Rosario",
    "distanciaKm": 700.0,
    "fechaInicio": "2025-11-24T10:00:00",
    "camion": {
      "patente": "AA123BB",
      "marca": "Mercedes-Benz",
      "modelo": "Actros 2651"
    },
    "transportista": {
      "nombre": "Carlos Rodríguez",
      "telefono": "+54911555666"
    }
  },
  "historialTramos": [
    {
      "ordenTramo": 1,
      "estado": "INICIADO",
      "fechaInicio": "2025-11-24T10:00:00",
      "fechaFin": null
    }
  ]
}
```

## 📦 Resumen de Endpoints por Microservicio

### MS-SOLICITUDES-V2 (Puerto 8081)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| POST | `/api/solicitudes` | Crear solicitud |
| GET | `/api/solicitudes` | Listar solicitudes |
| GET | `/api/solicitudes/{id}` | Obtener solicitud |
| PATCH | `/api/solicitudes/{id}/confirmar` | Confirmar solicitud |
| GET | `/api/solicitudes/estado/{estado}` | Filtrar por estado |
| POST | `/api/clientes` | Crear cliente |
| GET | `/api/contenedores` | Listar contenedores |

---

### MS-LOGISTICA (Puerto 8082)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| POST | `/api/rutas/calcular` | Calcular rutas tentativas |
| POST | `/api/tramos/{id}/asignar` | Asignar camión/transportista |
| PATCH | `/api/tramos/{id}/iniciar` | Iniciar tramo |
| PATCH | `/api/tramos/{id}/finalizar` | Finalizar tramo |
| GET | `/api/tramos/solicitud/{id}` | Listar tramos de solicitud |
| GET | `/api/camiones` | Listar camiones |
| GET | `/api/camiones/disponibles` | Camiones disponibles |
| POST | `/api/depositos` | Crear depósito |
| GET | `/api/transportistas` | Listar transportistas |

---

### MS-FACTURACION-V2 (Puerto 8084)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| POST | `/api/facturas/generar` | Generar factura |
| GET | `/api/facturas` | Listar facturas |
| GET | `/api/facturas/solicitud/{id}` | Obtener factura por solicitud |
| GET | `/api/tarifas/vigente` | Obtener tarifa vigente |
| POST | `/api/tarifas` | Crear tarifa |
| GET | `/api/estadias` | Listar estadías |
| GET | `/api/estadias/contenedor/{id}` | Estadías de contenedor |

---

### MS-TRACKING-V2 (Puerto 8083)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| GET | `/api/tracking/contenedor/{codigo}` | Tracking completo |
| GET | `/api/tracking/solicitud/{id}` | Tracking por solicitud |

---


http://localhost:8091/swagger-ui/index.html
http://localhost:8092/swagger-ui/index.html
http://localhost:8093/swagger-ui/index.html
http://localhost:8094/swagger-ui/index.html