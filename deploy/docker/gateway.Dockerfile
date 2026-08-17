FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -pl agent-gateway -am -DskipTests install
RUN mvn -q -pl agent-gateway -DskipTests package spring-boot:repackage

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/agent-gateway/target/agentteams-agent-gateway-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
