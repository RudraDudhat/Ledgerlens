# Built from the repository root so the image can be produced with the same context as the
# frontend; see docker-compose.yml.

# The React app calls /api with relative paths and relies on the Vite dev server to proxy them,
# which no static host does. Compiling the bundle into the jar's static resources serves both from
# one origin instead, so a deployment is a single service with no proxy rule and no CORS.
FROM node:20-alpine AS frontend
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci
COPY frontend/ ./frontend/
# vite.config.ts reads ../data to emit the sample batch at /sample/*, so it must be here at build
# time or the Upload screen's "Load sample data" resolves to nothing.
COPY data/ ./data/
RUN cd frontend && npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Carries no credentials: every value in it is read from the environment at startup.
COPY --from=build /build/src/main/resources/application-example.yml application.yml
# Ground truth for /api/metrics. Compose mounts ./data over this read-only; hosts with no volumes
# fall back to the copy baked in here rather than silently scoring nothing.
COPY data/ /app/data/
EXPOSE 8080
# The default heap is a quarter of the container, which is not enough on a 512 MB free tier.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
