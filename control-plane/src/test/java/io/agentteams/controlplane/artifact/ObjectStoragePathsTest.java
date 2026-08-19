package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectStoragePathsTest {
    @Test
    void buildsIsolatedConfigFilePath() {
        UUID snapshotId = UUID.randomUUID();

        assertThat(ObjectStoragePaths.configFile(snapshotId, "models/default.json"))
                .isEqualTo("configs/" + snapshotId + "/files/models/default.json");
    }

    @Test
    void rejectsTraversalAndAbsoluteConfigPaths() {
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> ObjectStoragePaths.configFile(snapshotId, "../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectStoragePaths.configFile(snapshotId, "/secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
