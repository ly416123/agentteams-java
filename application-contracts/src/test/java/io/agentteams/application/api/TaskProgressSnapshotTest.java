package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaskProgressSnapshotTest {
    @Test
    void carriesPhaseCountsProgressAndWaitingReason() {
        TaskProgressSnapshot snapshot = new TaskProgressSnapshot("EXECUTING", 25, 100, 25,
                "waiting for tool result");

        assertEquals("EXECUTING", snapshot.phase());
        assertEquals(25, snapshot.completed());
        assertEquals(100, snapshot.total());
        assertEquals(25, snapshot.progress());
        assertEquals("waiting for tool result", snapshot.waitingReason());
    }

    @Test
    void normalizesAnAbsentWaitingReasonToEmptyText() {
        TaskProgressSnapshot snapshot = new TaskProgressSnapshot("QUEUED", 0, 0, 0, null);

        assertEquals("", snapshot.waitingReason());
    }

    @Test
    void rejectsIllegalRanges() {
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot("RUNNING", -1, 1, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot("RUNNING", 2, 1, 100, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot("RUNNING", 1, 2, -1, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot("RUNNING", 1, 2, 101, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot("RUNNING", 0, 0, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressSnapshot(" ", 0, 0, 0, ""));
    }
}
