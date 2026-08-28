package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeConfigSnapshotTest {
    @Test
    void carriesImmutableLocalFileReferencesAlongsideValues() {
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(3, "sha-3",
                Map.of("model", "deepseek"), Map.of("models/default.json", Path.of("/tmp/config/models/default.json")));

        assertThat(snapshot.values()).containsEntry("model", "deepseek");
        assertThat(snapshot.files()).containsEntry("models/default.json", Path.of("/tmp/config/models/default.json"));
    }

    @Test
    void carriesImmutableSkillDirectoriesAlongsideFiles() {
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(3, "sha-3",
                Map.of("model", "deepseek"), Map.of(),
                Map.of("skill-a", Path.of("/tmp/config/skills/skill-a")));

        assertThat(snapshot.skillDirectories()).containsEntry("skill-a",
                Path.of("/tmp/config/skills/skill-a"));
    }
}
