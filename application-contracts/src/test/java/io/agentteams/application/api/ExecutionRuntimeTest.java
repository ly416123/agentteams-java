package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutionRuntimeTest {

    @Test
    void defaultsMissingOrBlankRuntimeToQwenPaw() {
        assertEquals(ExecutionRuntime.QWENPAW, ExecutionRuntime.from(null));
        assertEquals(ExecutionRuntime.QWENPAW, ExecutionRuntime.from("   "));
    }

    @Test
    void parsesSupportedRuntimeNamesIgnoringCaseAndWhitespace() {
        assertEquals(ExecutionRuntime.QWENPAW, ExecutionRuntime.from("  qWeNpAw "));
        assertEquals(ExecutionRuntime.AGENTSCOPE, ExecutionRuntime.from(" agentscope "));
    }

    @Test
    void rejectsUnknownRuntimeName() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ExecutionRuntime.from("unknown"));

        assertEquals("Unsupported execution runtime: unknown; expected QWENPAW or AGENTSCOPE",
                error.getMessage());
    }
}
