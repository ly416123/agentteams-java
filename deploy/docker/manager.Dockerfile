FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY deploy/docker/maven-settings.xml /root/.m2/settings.xml
COPY . .
RUN mvn -q -pl manager -am -DskipTests install
RUN mvn -q -pl manager -DskipTests package spring-boot:repackage

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/manager/target/agentteams-manager-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
