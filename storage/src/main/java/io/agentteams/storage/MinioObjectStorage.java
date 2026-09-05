package io.agentteams.storage;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** MinIO-backed implementation of the control-plane object storage port. */
public final class MinioObjectStorage implements ObjectStorage {

    private static final long MAX_PRESIGNED_EXPIRY_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final String bucket;

    public MinioObjectStorage(MinioObjectStorageConfig config) {
        this(buildClient(config), buildPresignClient(config), config.bucket());
    }

    MinioObjectStorage(MinioClient minioClient, String bucket) {
        this(minioClient, minioClient, bucket);
    }

    MinioObjectStorage(MinioClient minioClient, MinioClient presignClient, String bucket) {
        this.minioClient = Objects.requireNonNull(minioClient, "minioClient");
        this.presignClient = Objects.requireNonNull(presignClient, "presignClient");
        this.bucket = requireText(bucket, "bucket");
    }

    @Override
    public void upload(String objectKey, InputStream content, long contentLength, String contentType) {
        String key = requireObjectKey(objectKey);
        Objects.requireNonNull(content, "content");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(content, contentLength, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? DEFAULT_CONTENT_TYPE
                            : contentType)
                    .build());
        } catch (Exception error) {
            throw failure("upload", key, error);
        }
    }

    @Override
    public InputStream download(String objectKey) {
        String key = requireObjectKey(objectKey);
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception error) {
            throw failure("download", key, error);
        }
    }

    @Override
    public void delete(String objectKey) {
        String key = requireObjectKey(objectKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception error) {
            throw failure("delete", key, error);
        }
    }

    @Override
    public URL presignGet(String objectKey, Duration expiry) {
        String key = requireObjectKey(objectKey);
        long expirySeconds = validateExpiry(expiry);
        try {
            String url = presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry((int) expirySeconds)
                    .build());
            return new URL(url);
        } catch (MalformedURLException error) {
            throw failure("presign", key, error);
        } catch (Exception error) {
            throw failure("presign", key, error);
        }
    }

    @Override
    public URL presignPut(String objectKey, String contentType, Duration expiry) {
        String key = requireObjectKey(objectKey);
        long expirySeconds = validateExpiry(expiry);
        try {
            String url = presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT).bucket(bucket).object(key).expiry((int) expirySeconds).build());
            return new URL(url);
        } catch (Exception error) {
            throw failure("presign upload", key, error);
        }
    }

    private static MinioClient buildClient(MinioObjectStorageConfig config) {
        Objects.requireNonNull(config, "config");
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(config.endpoint())
                .credentials(config.accessKey(), config.secretKey());
        if (config.region() != null && !config.region().isBlank()) {
            builder.region(config.region());
        }
        return builder.build();
    }

    private static MinioClient buildPresignClient(MinioObjectStorageConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.presignEndpoint() == null || config.presignEndpoint().isBlank()
                || config.presignEndpoint().equals(config.endpoint())) {
            return buildClient(config);
        }
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(config.presignEndpoint())
                .credentials(config.accessKey(), config.secretKey());
        if (config.region() != null && !config.region().isBlank()) {
            builder.region(config.region());
        }
        return builder.build();
    }

    private static long validateExpiry(Duration expiry) {
        if (expiry == null
                || expiry.isZero()
                || expiry.isNegative()
                || expiry.getSeconds() < 1
                || expiry.getSeconds() > MAX_PRESIGNED_EXPIRY_SECONDS
                || expiry.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("expiry must be between one second and seven days");
        }
        return expiry.getSeconds();
    }

    private static String requireObjectKey(String objectKey) {
        return requireText(objectKey, "objectKey");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static ObjectStorageException failure(String operation, String objectKey, Exception cause) {
        return new ObjectStorageException(
                "object storage " + operation + " failed for " + objectKey, cause);
    }
}
