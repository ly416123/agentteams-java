package io.agentteams.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires the MinIO-backed {@link ObjectStorage} for any application that depends on this module;
 * the bean used to live in ControlPlaneConfiguration and moved here so the storage port can be
 * reused without the control plane. Off by default, exactly like the original wiring.
 */
@AutoConfiguration
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "agentteams.storage.enabled", havingValue = "true")
    ObjectStorage objectStorage(
            @Value("${agentteams.storage.endpoint}") String endpoint,
            @Value("${agentteams.storage.bucket}") String bucket,
            @Value("${agentteams.storage.access-key}") String accessKey,
            @Value("${agentteams.storage.secret-key}") String secretKey,
            @Value("${agentteams.storage.presign-endpoint:}") String presignEndpoint,
            @Value("${agentteams.storage.region:}") String region) {
        return new MinioObjectStorage(new MinioObjectStorageConfig(
                endpoint, bucket, accessKey, secretKey, presignEndpoint, region));
    }
}
