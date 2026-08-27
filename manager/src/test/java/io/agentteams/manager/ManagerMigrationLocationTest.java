package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;

class ManagerMigrationLocationTest {

    @Test
    void keepsManagerMigrationsOutsideControlPlaneDefaultLocation() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        URL managerMigration = loader.getResource("db/manager-migration/V1__manager_sessions.sql");
        URL defaultMigration = loader.getResource("db/migration/V1__manager_sessions.sql");

        assertThat(managerMigration).as("Manager migration location").isNotNull();
        assertThat(defaultMigration).as("Control Plane default migration location").isNull();
    }
}
