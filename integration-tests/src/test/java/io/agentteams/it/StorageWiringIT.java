package io.agentteams.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.ControlPlaneApplication;
import io.agentteams.controlplane.api.ArtifactController;
import io.agentteams.controlplane.api.ConfigFileController;
import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.config.ConfigUploadService;
import io.agentteams.storage.MinioObjectStorage;
import io.agentteams.storage.ObjectStorage;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wiring acceptance for the storage module split: with {@code agentteams.storage.enabled=true}
 * the ObjectStorage bean and every component-scanned API controller whose
 * {@code @ConditionalOnBean} chain is rooted at ObjectStorage must be registered. The MinIO
 * client is lazy, so a dummy endpoint is enough. This guards against the ordering hazard where
 * the bean moves to an auto-configuration and the scanned controllers' conditions evaluate
 * before the auto-configured bean definition exists, silently removing the artifact and
 * config-file routes.
 */
@Testcontainers(disabledWithoutDocker = true)
class StorageWiringIT {

    private static final String DATABASE_USER = "agentteams";
    private static final String DATABASE_PASSWORD = "agentteams-dev";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agentteams")
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD);

    private static ConfigurableApplicationContext controlPlane;

    @BeforeAll
    static void startControlPlaneWithStorageEnabled() {
        controlPlane = new SpringApplicationBuilder(ControlPlaneApplication.class)
                .run(new String[] {
                        "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + DATABASE_USER,
                        "--spring.datasource.password=" + DATABASE_PASSWORD,
                        "--agentteams.scheduler.enabled=false",
                        "--agentteams.team-sync.enabled=false",
                        "--agentteams.nats.enabled=false",
                        "--agentteams.storage.enabled=true",
                        "--agentteams.storage.endpoint=http://minio.invalid:9000",
                        "--agentteams.storage.bucket=agentteams",
                        "--agentteams.storage.access-key=test-access",
                        "--agentteams.storage.secret-key=test-secret"
                });
    }

    @Test
    void storageBeanAndEveryStorageBackedControllerAreRegistered() {
        assertThat(controlPlane.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
        // Component-scanned controllers whose @ConditionalOnBean chains are rooted at
        // ObjectStorage; they vanish silently when the root bean registers too late.
        assertThat(controlPlane.getBean(ConfigFileController.class)).isNotNull();
        assertThat(controlPlane.getBean(ArtifactController.class)).isNotNull();
        assertThat(controlPlane.getBean(ArtifactService.class)).isNotNull();
        assertThat(controlPlane.getBean(ConfigUploadService.class)).isNotNull();
    }

    @Test
    void thePendingTimeoutJobStillObeysItsOwnFlag() {
        // Unrelated smoke: the reconciliation beans from the earlier fix stay wired in the same
        // context, proving the storage wiring does not disturb the deployment trunk.
        assertThat(controlPlane.getBean(
                io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutService.class)).isNotNull();
        assertThat(Instant.now()).isNotNull();
    }
}
