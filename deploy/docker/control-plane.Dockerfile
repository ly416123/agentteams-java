FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl control-plane -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/control-plane/target/agentteams-control-plane-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
