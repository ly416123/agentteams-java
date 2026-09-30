FROM eclipse-temurin:21-jre
WORKDIR /app
COPY operator/target/agentteams-operator-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
