FROM maven:3.9.16-eclipse-temurin-17 AS compile
WORKDIR /source
COPY deploy/docker/TaskSandboxRunner.java /source/TaskSandboxRunner.java
RUN javac --release 17 -d /out /source/TaskSandboxRunner.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=compile /out /app

USER 10001:10001
EXPOSE 7443
ENTRYPOINT ["java", "-cp", "/app", "TaskSandboxRunner"]
