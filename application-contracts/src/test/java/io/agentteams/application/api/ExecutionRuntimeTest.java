package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutionRuntimeTest {

    @Test
    void defaultsMissingOrBlankRuntimeToAgentScope() {
        assertEquals(ExecutionRuntime.AGENTSCOPE, ExecutionRuntime.from(null));
        assertEquals(ExecutionRuntime.AGENTSCOPE, ExecutionRuntime.from("   "));
    }

    @Test
    void parsesSupportedRuntimeNamesIgnoringCaseAndWhitespace() {
        assertEquals(ExecutionRuntime.AGENTSCOPE, ExecutionRuntime.from(" agentscope "));
    }

    @Test
    void rejectsUnknownRuntimeName() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ExecutionRuntime.from("unknown"));

        assertEquals("Unsupported execution runtime: unknown; expected AGENTSCOPE",
                error.getMessage());
    }
}
