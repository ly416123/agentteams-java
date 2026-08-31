package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TaskResultManifestTest {
    @Test
    void containsOnlyResultAndArtifactMetadataReferences() {
        TaskResultManifest.ArtifactMetadata artifact = new TaskResultManifest.ArtifactMetadata(
                "report.json", "urn:agentteams:artifact:report", "application/json", 128,
                "abc123", 2, "FINAL", TaskEventVisibility.REQUESTER);
        List<TaskResultManifest.ArtifactMetadata> artifacts = new ArrayList<>(List.of(artifact));

        TaskResultManifest manifest = new TaskResultManifest(UUID.randomUUID(), UUID.randomUUID(),
                "SUCCEEDED", "Task completed", artifacts);

        assertEquals("SUCCEEDED", manifest.status());
        assertEquals("Task completed", manifest.summary());
        assertEquals(List.of(artifact), manifest.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> manifest.artifacts().clear());
        artifacts.clear();
        assertEquals(1, manifest.artifacts().size());
    }

    @Test
    void rejectsInvalidManifestAndArtifactMetadata() {
        assertThrows(NullPointerException.class, () -> new TaskResultManifest(null, UUID.randomUUID(),
                "SUCCEEDED", "summary", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskResultManifest(UUID.randomUUID(),
                UUID.randomUUID(), " ", "summary", List.of()));
        assertThrows(NullPointerException.class, () -> new TaskResultManifest(UUID.randomUUID(),
                UUID.randomUUID(), "SUCCEEDED", "summary", null));
        assertThrows(IllegalArgumentException.class, () -> new TaskResultManifest.ArtifactMetadata(
                "report.json", "urn:agentteams:artifact:report", "application/json", -1,
                "abc123", 1, "FINAL", TaskEventVisibility.REQUESTER));
        assertThrows(NullPointerException.class, () -> new TaskResultManifest.ArtifactMetadata(
                "report.json", "urn:agentteams:artifact:report", "application/json", 1,
                "abc123", 1, "FINAL", null));
    }
}
