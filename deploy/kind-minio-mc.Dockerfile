FROM alpine:3.22

ARG RELEASE
ARG SOURCE_COMMIT
LABEL org.opencontainers.image.source="https://github.com/minio/mc" \
      org.opencontainers.image.version="$RELEASE" \
      org.opencontainers.image.revision="$SOURCE_COMMIT"

RUN apk add --no-cache ca-certificates
COPY mc /usr/local/bin/mc
ENTRYPOINT ["mc"]
