package io.agentteams.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auto-configuration must keep the wiring contract that ControlPlaneConfiguration used to
 * provide: no storage bean unless the feature flag is on, and a MinIO-backed ObjectStorage when
 * the required endpoint properties are present.
 */
class StorageAutoConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @Test
    void registersNoStorageByDefault() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void wiresMinioObjectStorageWhenEnabledWithEndpointProperties() {
        context.withPropertyValues(
                "agentteams.storage.enabled=true",
                "agentteams.storage.endpoint=http://minio:9000",
                "agentteams.storage.bucket=agentteams",
                "agentteams.storage.access-key=access",
                "agentteams.storage.secret-key=secret").run(ctx -> {
            assertThat(ctx).hasSingleBean(ObjectStorage.class);
            assertThat(ctx.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
        });
    }

    @Test
    void failsFastWhenEnabledWithoutAnEndpoint() {
        context.withPropertyValues("agentteams.storage.enabled=true").run(ctx ->
                assertThat(ctx).hasFailed());
    }
}
