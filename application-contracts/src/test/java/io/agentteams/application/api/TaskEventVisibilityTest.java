package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class TaskEventVisibilityTest {
    @Test
    void exposesTheArchitectureVisibilityLevels() {
        assertArrayEquals(new TaskEventVisibility[] {
                TaskEventVisibility.REQUESTER,
                TaskEventVisibility.PROJECT_MEMBER,
                TaskEventVisibility.TENANT_ADMIN,
                TaskEventVisibility.SECURITY_AUDITOR,
                TaskEventVisibility.INTERNAL_ONLY
        }, TaskEventVisibility.values());
    }
}
