# Built from the repository root so the image can be produced with the same context as the
# frontend; see docker-compose.yml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Carries no credentials: every value in it is read from the environment at startup.
COPY --from=build /build/src/main/resources/application-example.yml application.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
