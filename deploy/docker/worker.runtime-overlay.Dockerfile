FROM eclipse-temurin:21-jre
WORKDIR /app
COPY agentteams-agent-worker-0.1.0-SNAPSHOT-boot.jar /app/app.jar
USER 10001
EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
