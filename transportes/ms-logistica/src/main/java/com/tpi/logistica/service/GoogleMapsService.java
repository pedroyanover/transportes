package com.tpi.logistica.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Servicio para calcular distancias usando Google Maps Distance Matrix API
 */
@Service
@Slf4j
public class GoogleMapsService {
    
    @Value("${google.maps.api.key}")
    private String apiKey;
    
    private static final String GOOGLE_MAPS_API_URL = "https://maps.googleapis.com/maps/api/distancematrix/json";
    private final WebClient webClient;
    
    public GoogleMapsService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(GOOGLE_MAPS_API_URL).build();
    }
    
    /**
     * Calcula la distancia en kilómetros entre dos direcciones usando Google Maps Distance Matrix API.
     * Si la API falla, usa fórmula Haversine como fallback.
     * 
     * @param origenDireccion Dirección de origen completa
     * @param destinoDireccion Dirección de destino completa
     * @return Distancia en kilómetros
     */
    public Double calcularDistancia(String origenDireccion, String destinoDireccion) {
        log.info("Calculando distancia real usando Google Maps Distance Matrix API");
        log.info("Origen: {} | Destino: {}", origenDireccion, destinoDireccion);
        
        // Si no hay API key configurada, usar fallback directamente
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("⚠️ Google Maps API key no configurada. Usando distancias estimadas.");
            return usarDistanciaEstimada(origenDireccion, destinoDireccion);
        }
        
        try {
            // URL usando la API key inyectada desde propiedades
            String url = String.format(
                "https://maps.googleapis.com/maps/api/distancematrix/json?destinations=%s&origins=%s&units=metric&key=%s",
                java.net.URLEncoder.encode(destinoDireccion, "UTF-8"),
                java.net.URLEncoder.encode(origenDireccion, "UTF-8"),
                apiKey
            );
            
            log.debug("🌍 URL API: {} (key: {}****)", 
                    url.replaceAll("key=.*", "key=****"), 
                    apiKey.substring(0, Math.min(10, apiKey.length())));
            
            // Llamada a Google Maps Distance Matrix API
            String responseBody = WebClient.create()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("📦 Response JSON raw:\n{}", responseBody);
            
            // Deserializar la respuesta
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var response = objectMapper.readValue(responseBody, DistanceMatrixResponse.class);
            
            // Validar respuesta
            if (response == null || !"OK".equals(response.status)) {
                log.warn("Google Maps API no devolvió resultado OK. Status: {}, Error: {}", 
                        response != null ? response.status : "null",
                        response != null ? response.error_message : "null");
                return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            }
            
            if (response.rows == null || response.rows.isEmpty()) {
                log.warn("Google Maps API no devolvió filas");
                return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            }
            
            var firstRow = response.rows.get(0);
            if (firstRow.elements == null || firstRow.elements.isEmpty()) {
                log.warn("Fila sin elementos");
                return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            }
            
            var firstElement = firstRow.elements.get(0);
            if (!"OK".equals(firstElement.status)) {
                log.warn("Elemento con status: {}. Posible dirección no encontrada", firstElement.status);
                return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            }
            
            if (firstElement.distance == null) {
                log.warn("Elemento sin información de distancia");
                return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            }
            
            // Convertir de metros a kilómetros y redondear
            double distanciaKm = firstElement.distance.value / 1000.0;
            double distanciaRedondeada = Math.round(distanciaKm * 100.0) / 100.0;
            
            log.info("✅ Distancia obtenida de Google Maps: {} km ({})", 
                    distanciaRedondeada, firstElement.distance.text);
            
            return distanciaRedondeada;
            
        } catch (WebClientResponseException e) {
            log.error("❌ Error al llamar a Google Maps API: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return usarDistanciaEstimada(origenDireccion, destinoDireccion);
            
        } catch (Exception e) {
            log.error("❌ Error inesperado al calcular distancia con Google Maps: {}", e.getMessage(), e);
            return usarDistanciaEstimada(origenDireccion, destinoDireccion);
        }
    }
    
    /**
     * Método de fallback: Usa distancias estimadas basadas en rutas conocidas de Argentina
     */
    private Double usarDistanciaEstimada(String origen, String destino) {
        log.warn("🔄 Usando distancias estimadas como fallback");

        // intentar calcular vía coordenadas (Haversine) ya que suele ser más preciso
        try {
            double[] coordsOrigen = obtenerCoordenadas(origen);
            double[] coordsDestino = obtenerCoordenadas(destino);
            if (coordsOrigen != null && coordsDestino != null) {
                double haversine = calcularDistanciaHaversine(
                        coordsOrigen[0], coordsOrigen[1],
                        coordsDestino[0], coordsDestino[1]
                );
                log.info("✅ Distancia calculada por Haversine: {} km", haversine);
                return haversine;
            }
        } catch (Exception e) {
            log.warn("⚠️ No se pudo calcular Haversine para fallback: {}", e.getMessage());
        }

        // Normalizar nombres (case-insensitive, sin "Argentina")
        String origenNorm = origen.toLowerCase().replace(", argentina", "").trim();
        String destinoNorm = destino.toLowerCase().replace(", argentina", "").trim();
        
        // Mapa de distancias reales aproximadas (km) entre ciudades argentinas
        var distancias = new java.util.HashMap<String, Double>();
        distancias.put("buenos aires-mendoza", 1050.0);
        distancias.put("mendoza-buenos aires", 1050.0);
        distancias.put("buenos aires-cordoba", 700.0);
        distancias.put("cordoba-buenos aires", 700.0);
        distancias.put("buenos aires-bariloche", 1650.0);
        distancias.put("bariloche-buenos aires", 1650.0);
        distancias.put("buenos aires-salta", 1590.0);
        distancias.put("salta-buenos aires", 1590.0);
        distancias.put("buenos aires-rosario", 300.0);
        distancias.put("rosario-buenos aires", 300.0);
        distancias.put("cordoba-mendoza", 550.0);
        distancias.put("mendoza-cordoba", 550.0);
        distancias.put("rosario-cordoba", 400.0);
        distancias.put("cordoba-rosario", 400.0);
        distancias.put("neuquen-bariloche", 430.0);
        distancias.put("bariloche-neuquen", 430.0);
        
        String clave = origenNorm + "-" + destinoNorm;
        Double distancia = distancias.get(clave);
        
        if (distancia != null) {
            log.info("✅ Distancia estimada: {} km", distancia);
            return distancia;
        }
        
        // Si no hay match exacto, devolver distancia por defecto (>1000km para generar múltiples rutas)
        log.warn("⚠️ No hay distancia estimada para: {} → {}. Usando 1500km por defecto", origen, destino);
        return 1500.0;
    }
    
    /**
     * Calcula tiempo estimado de viaje en horas.
     * Asume velocidad promedio de 80 km/h para transporte de carga.
     * 
     * @param distanciaKm Distancia en kilómetros
     * @return Tiempo estimado en horas
     */
    public Double calcularTiempoEstimado(Double distanciaKm) {
        final double VELOCIDAD_PROMEDIO = 80.0; // km/h
        double tiempoHoras = distanciaKm / VELOCIDAD_PROMEDIO;
        return Math.round(tiempoHoras * 100.0) / 100.0;
    }

    /**
     * Fórmula de Haversine para estimar la distancia recta entre dos coordenadas.
     * @param lat1 latitud origen
     * @param lon1 longitud origen
     * @param lat2 latitud destino
     * @param lon2 longitud destino
     * @return distancia en kilómetros
     */
    private double calcularDistanciaHaversine(double lat1, double lon1,
                                              double lat2, double lon2) {
        final int R = 6371; // radio de la Tierra en km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }
    
    /**
     * Obtiene las coordenadas (lat, lon) de una dirección usando Google Geocoding API.
     * 
     * @param direccion Dirección completa
     * @return Array [latitud, longitud] o null si no se puede obtener
     */
    public double[] obtenerCoordenadas(String direccion) {
        // Si no hay API key configurada, usar fallback
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.debug("⚠️ Google Maps API key no configurada. Usando coordenadas estimadas para: {}", direccion);
            return obtenerCoordenadasEstimadas(direccion);
        }
        
        try {
            String url = String.format(
                "https://maps.googleapis.com/maps/api/geocode/json?address=%s&key=%s",
                java.net.URLEncoder.encode(direccion, "UTF-8"),
                apiKey
            );
            
            var response = WebClient.create()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(GeocodingResponse.class)
                    .block();
            
            if (response != null && "OK".equals(response.status) && 
                response.results != null && !response.results.isEmpty()) {
                
                var location = response.results.get(0).geometry.location;
                log.info("📍 Coordenadas de '{}': [{}, {}]", direccion, location.lat, location.lng);
                return new double[]{location.lat, location.lng};
            }
            
            log.warn("⚠️ No se pudieron obtener coordenadas para: {}", direccion);
            return obtenerCoordenadasEstimadas(direccion);
            
        } catch (Exception e) {
            log.error("❌ Error al obtener coordenadas de '{}': {}", direccion, e.getMessage());
            return obtenerCoordenadasEstimadas(direccion);
        }
    }
    
    /**
     * Fallback: Retorna coordenadas estimadas para principales ciudades argentinas
     */
    private double[] obtenerCoordenadasEstimadas(String direccion) {
        String dir = direccion.toLowerCase();
        
        var coordenadas = new java.util.HashMap<String, double[]>();
        // Principales ciudades argentinas
        coordenadas.put("buenos aires", new double[]{-34.6037, -58.3816});
        coordenadas.put("cordoba", new double[]{-31.4201, -64.1888});
        coordenadas.put("rosario", new double[]{-32.9442, -60.6505});
        coordenadas.put("mendoza", new double[]{-32.8895, -68.8458});
        coordenadas.put("bariloche", new double[]{-41.1345, -71.3105});
        coordenadas.put("salta", new double[]{-24.7859, -65.4117});
        coordenadas.put("la plata", new double[]{-34.9215, -57.9545});
        coordenadas.put("mar del plata", new double[]{-38.0055, -57.5426});
        coordenadas.put("bahia blanca", new double[]{-38.7183, -62.2655});
        coordenadas.put("neuquen", new double[]{-38.9516, -68.0591});
        
        // Buscar coincidencia
        for (String key : coordenadas.keySet()) {
            if (dir.contains(key)) {
                log.debug("📍 Coordenadas estimadas para '{}': {}", direccion, java.util.Arrays.toString(coordenadas.get(key)));
                return coordenadas.get(key);
            }
        }
        
        // Fallback genérico: Buenos Aires
        log.warn("⚠️ No se encontraron coordenadas para '{}'. Usando Buenos Aires por defecto.", direccion);
        return new double[]{-34.6037, -58.3816};
    }
    
    // ===== DTOs para Distance Matrix API =====
    
    @lombok.Data
    public static class DistanceMatrixResponse {
        private java.util.List<String> destination_addresses;
        private java.util.List<String> origin_addresses;
        private java.util.List<Row> rows;
        private String status;
        private String error_message; // Campo para manejar errores de API
    }
    
    @lombok.Data
    public static class Row {
        private java.util.List<Element> elements;
    }
    
    @lombok.Data
    public static class Element {
        private Distance distance;
        private Duration duration;
        private String status;
    }
    
    @lombok.Data
    public static class Distance {
        private String text;
        private Integer value; // metros
    }
    
    @lombok.Data
    public static class Duration {
        private String text;
        private Integer value; // segundos
    }
    
    // ===== DTOs para Geocoding API =====
    
    @lombok.Data
    public static class GeocodingResponse {
        private java.util.List<GeocodingResult> results;
        private String status;
    }
    
    @lombok.Data
    public static class GeocodingResult {
        private Geometry geometry;
        private String formatted_address;
    }
    
    @lombok.Data
    public static class Geometry {
        private Location location;
    }
    
    @lombok.Data
    public static class Location {
        private Double lat;
        private Double lng;
    }
}
