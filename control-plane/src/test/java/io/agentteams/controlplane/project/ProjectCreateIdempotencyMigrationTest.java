package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ProjectCreateIdempotencyMigrationTest {

    @Test
    void defersProjectForeignKeyUntilProjectCreationTransactionCompletes() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V57__project_create_idempotency_fk_deferred.sql")) {
            assertThat(stream).as("project creation foreign-key migration").isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("DEFERRABLE INITIALLY DEFERRED");
        }
    }
}
