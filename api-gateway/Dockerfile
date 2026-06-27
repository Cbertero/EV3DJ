# =========================================================================
# Etapa 1: Build - compila el proyecto con Maven y JDK 17
# =========================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Etapa 2: Runtime - imagen liviana solo con el JRE
# =========================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

RUN chown -R nobody:nobody /app
USER nobody

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
