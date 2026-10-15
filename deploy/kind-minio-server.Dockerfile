FROM alpine:3.22

ARG RELEASE
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.version="$RELEASE" \
      org.opencontainers.image.revision="$SOURCE_COMMIT"

RUN apk add --no-cache ca-certificates
COPY minio /usr/local/bin/minio

EXPOSE 9000 9001
VOLUME ["/data"]
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
