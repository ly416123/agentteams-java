# Reuses an already available linux/amd64 JRE service image when the build host
# cannot pull the multi-architecture Maven/JRE base layers. The application jar
# is still produced by the local Maven build and replaces only /app/app.jar.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
WORKDIR /app
ARG APP_JAR
COPY ${APP_JAR} /app/app.jar
