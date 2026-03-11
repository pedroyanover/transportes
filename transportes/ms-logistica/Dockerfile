# Usamos el jar ya compilado localmente para evitar descargar dependencias en el build.
# Asegurarse de ejecutar `./mvnw -DskipTests package` antes de build.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ms-logistica-1.0.0.jar app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
