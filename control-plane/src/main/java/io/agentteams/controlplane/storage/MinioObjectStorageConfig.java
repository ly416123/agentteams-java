package io.agentteams.controlplane.storage;

public record MinioObjectStorageConfig(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String presignEndpoint,
        String region) {

    public MinioObjectStorageConfig(String endpoint, String bucket, String accessKey, String secretKey) {
        this(endpoint, bucket, accessKey, secretKey, null, null);
    }

    public MinioObjectStorageConfig(String endpoint, String bucket, String accessKey, String secretKey,
            String presignEndpoint) {
        this(endpoint, bucket, accessKey, secretKey, presignEndpoint, null);
    }

    public MinioObjectStorageConfig {
        requireText(endpoint, "endpoint");
        requireText(bucket, "bucket");
        requireText(accessKey, "accessKey");
        requireText(secretKey, "secretKey");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
