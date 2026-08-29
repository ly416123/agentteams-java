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
        URL teamMigrationUrl = loader.getResource("db/manager-migration/V2__manager_session_team_scope.sql");
        assertThat(teamMigrationUrl).as("Manager team scope migration").isNotNull();
        String teamMigration = java.nio.file.Files.readString(java.nio.file.Path.of(teamMigrationUrl.toURI()));
        assertThat(teamMigration).contains("team_id").contains("manager_sessions_scope_idempotency_key");
    }
}
