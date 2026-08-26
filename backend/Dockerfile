FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
COPY --from=build /build/src/main/resources/application-example.yml application.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
