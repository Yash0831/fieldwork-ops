# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM first so dependency layers are cached across code changes.
COPY backend/pom.xml ./pom.xml
RUN mvn -q dependency:go-offline

COPY backend/src ./src
RUN mvn -q -DskipTests package

# ---------- Runtime stage ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as a non-root user; least privilege for the container.
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

COPY --from=build /build/target/fieldwork-ops-*.jar app.jar

EXPOSE 8080

# JVM tuned for containers: respect cgroup memory limits, fast startup.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
