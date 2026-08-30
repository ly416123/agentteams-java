package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;

class ManagerMigrationLocationTest {

    @Test
    void keepsManagerMigrationsOutsideControlPlaneDefaultLocation() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        URL managerMigration = loader.getResource("db/manager-migration/V1__manager_sessions.sql");
        URL defaultMigration = loader.getResource("db/migration/V1__manager_sessions.sql");

        assertThat(managerMigration).as("Manager migration location").isNotNull();
        assertThat(defaultMigration).as("Control Plane default migration location").isNull();
        URL teamMigrationUrl = loader.getResource("db/manager-migration/V3__manager_session_team_scope.sql");
        assertThat(teamMigrationUrl).as("Manager team scope migration").isNotNull();
        String teamMigration = java.nio.file.Files.readString(java.nio.file.Path.of(teamMigrationUrl.toURI()));
        assertThat(teamMigration).contains("team_id").contains("manager_sessions_scope_idempotency_key");
        assertThat(loader.getResource("db/manager-migration/V4__conversation_persistence.sql"))
                .as("Conversation persistence migration").isNotNull();
        assertThat(loader.getResource("db/manager-migration/V5__conversation_ownership_and_version.sql"))
                .as("Conversation ownership/version migration").isNotNull();
        assertThat(loader.getResource("db/manager-migration/V6__conversation_message_reservations.sql"))
                .as("Conversation message reservation migration").isNotNull();
        assertThat(loader.getResource("db/manager-migration/V7__conversation_event_identity.sql"))
                .as("Conversation event identity migration").isNotNull();
        String ownershipMigration = java.nio.file.Files.readString(java.nio.file.Path.of(
                loader.getResource("db/manager-migration/V5__conversation_ownership_and_version.sql").toURI()));
        assertThat(ownershipMigration).doesNotContain("DEFAULT 'legacy'");
    }
}
