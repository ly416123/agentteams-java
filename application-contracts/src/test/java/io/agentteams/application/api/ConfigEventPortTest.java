package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigEventPortTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void acceptsStructuredResourceResultsAndCopiesTheList() {
        var result = new ConfigEventPort.ResourceApplyResult("SKILL", "skill-a", "7",
                "sha256:expected", "sha256:observed", "APPLIED", "");
        var mutable = new java.util.ArrayList<ConfigEventPort.ResourceApplyResult>();
        mutable.add(result);

        var command = new ConfigEventPort.ConfigAppliedCommand(ID, ID, ID, ID, 7, true, "",
                Instant.EPOCH, "gateway", "corr-1", mutable);
        mutable.clear();

        assertEquals(List.of(result), command.resourceResults());
    }

    @Test
    void rejectsUncontrolledResourceStatusOrFailureCategory() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigEventPort.ResourceApplyResult("MCP", "server-a", "3",
                "sha256:expected", "", "FAILED", "VENDOR_ERROR"));
        assertThrows(IllegalArgumentException.class, () -> new ConfigEventPort.ResourceApplyResult("MCP", "server-a", "3",
                "sha256:expected", "", "APPLIED", "DOWNLOAD_FAILED"));
    }
}
