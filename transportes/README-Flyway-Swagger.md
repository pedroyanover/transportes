README - Convención Flyway + Swagger
=====================================

Resumen breve
-------------
Este documento define la convención usada en este repo para:
- Ejecutar migraciones de BD con Flyway de forma consistente entre microservicios.
- Exponer documentación OpenAPI/Swagger con `springdoc` en cada servicio.

Objetivos
---------
- Que todos los servicios que gestionan datos tengan migraciones en `src/main/resources/db/migration/ms_<servicio>`.
- Que Flyway se pueda habilitar/deshabilitar desde variables de entorno (compatible con `docker-compose`).
- Que todos los servicios expongan OpenAPI/Swagger (configurable por perfil).

Ubicaciones y dependencias
--------------------------
- Migraciones: `src/main/resources/db/migration/ms_<servicio>/V1__init.sql`, etc.
- Dependencia Maven (ya incluida en la mayoría de `pom.xml`):
  - `org.flywaydb:flyway-core`
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` (para Spring MVC projects)

Convención de `application.properties` (ejemplo mínimo por servicio)
-------------------------------------------------------------------
- Swagger (siempre disponible por defecto en desarrollo):
  - `springdoc.api-docs.path=/api-docs`
  - `springdoc.swagger-ui.path=/swagger-ui.html`

- Flyway (usar env vars para override):
  - `spring.flyway.enabled=${SPRING_FLYWAY_ENABLED:true}`
  - `spring.flyway.locations=${SPRING_FLYWAY_LOCATIONS:classpath:db/migration/ms_<servicio>}`
  - `spring.flyway.schemas=${SPRING_FLYWAY_SCHEMAS:ms_<servicio>}`
  - `spring.flyway.create-schemas=${SPRING_FLYWAY_CREATE_SCHEMAS:true}`

Notas y recomendaciones
----------------------
- Habilitar Flyway sólo en servicios que "poseen" su esquema. Si varios servicios comparten la misma base, decidir quién es responsable de ejecutar migraciones (mejor: CI o servicio único responsable).
- `docker-compose.yml` puede definir las variables `SPRING_FLYWAY_*` por servicio; esta convención respeta esas variables.
- Para entornos `prod`, se recomienda que las migraciones las ejecute el pipeline de CI/CD o un job controlado (evita sorpresas durante el arranque concurrente de muchos servicios).
- Swagger: para entornos productivos oculta o protege el UI, dejando los endpoints de OpenAPI disponibles sólo para usuarios autenticados o en despliegues internos.

Checklist para añadir un nuevo servicio con la convención
---------------------------------------------------------
- Añadir dependencia `flyway-core` en `pom.xml` (si el servicio usa BD).
- Crear carpeta `src/main/resources/db/migration/ms_<servicio>` y añadir scripts `V1__...`, `V2__...`.
- Añadir al `application.properties` las claves Flyway estándar (ver arriba).
- Añadir `springdoc` dependency en `pom.xml` y las propiedades de Swagger en `application.properties`.
- Actualizar `docker-compose.yml` (opcional) con `SPRING_FLYWAY_LOCATIONS` y demás variables.

Comandos útiles
---------------
- Build local (skip tests):

```powershell
cd transportes/ms-<servicio>
.\mvnw.cmd -DskipTests package
```

- Levantar todo con Docker Compose (usa las variables definidas en `docker-compose.yml`):

```powershell
cd transportes
docker-compose up --build
```

Cambios aplicados en este repo
------------------------------
- Se añadieron propiedades estándar Flyway/Swagger en los `application.properties` de los microservicios para un comportamiento homogéneo (permitiendo override por env var).

Preguntas abiertas / decisiones por tomar
----------------------------------------
- ¿Quién debe ejecutar las migraciones en producción: cada servicio al arrancar o el pipeline/servicio central?
- ¿Deseas que Swagger UI esté protegido en `prod` por defecto (requiere configuración adicional)?

Si quieres, genero un pequeño `CONTRIBUTING.md` o un arquetipo/maven archetype para nuevos microservicios con la configuración por defecto.



ms-solicitudes: http://localhost:8091/swagger-ui.html
ms-logistica: http://localhost:8092/swagger-ui.html
ms-facturacion: http://localhost:8093/swagger-ui.html
ms-tracking: http://localhost:8094/swagger-ui.html