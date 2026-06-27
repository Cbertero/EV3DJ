# =========================================================================
# Etapa 1: Build - compila el proyecto con Maven y JDK 17
# =========================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Copiamos primero el pom.xml para aprovechar el cache de capas de Docker:
# si solo cambia el código fuente, no se vuelven a descargar las dependencias.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Etapa 2: Runtime - imagen liviana solo con el JRE (sin Maven, sin JDK completo)
# =========================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

# Buenas prácticas de seguridad: ejecutar como usuario no-root.
# Alpine ya trae el usuario/grupo "nobody" predefinido.
WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

RUN chown -R nobody:nobody /app
USER nobody

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
