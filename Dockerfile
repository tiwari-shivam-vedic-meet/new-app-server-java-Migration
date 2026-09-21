# ---- Build stage: compiles and runs tests with Maven (no local JDK/Maven needed) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies first for faster rebuilds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Build the jar. Tests run by default — the parity gates must pass to produce an image.
RUN mvn -B clean package

# ---- Runtime stage: small JRE image, non-root ----
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# Run as an unprivileged user
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /build/target/*.jar app.jar
USER app

EXPOSE 8081

# Container-aware memory + fast random for JWT/AES
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Docker/K8s health check hits the actuator readiness probe
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
