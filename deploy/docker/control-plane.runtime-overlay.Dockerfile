FROM eclipse-temurin:21-jre
WORKDIR /app
COPY agentteams-control-plane-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
