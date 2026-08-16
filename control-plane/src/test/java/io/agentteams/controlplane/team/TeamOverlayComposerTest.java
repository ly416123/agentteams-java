package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeamOverlayComposerTest {
    @Test
    void mergesNestedOverlaysWithoutMutatingBase() {
        String base = "{\"model\":\"deepseek\",\"limits\":{\"tokens\":100,\"timeout\":30}}";
        String effective = new TeamOverlayComposer().compose(base,
                "{\"limits\":{\"timeout\":60},\"team\":\"research\"}",
                "{\"limits\":{\"tokens\":200}} ");
        assertThat(effective).isEqualTo("{\"model\":\"deepseek\",\"limits\":{\"tokens\":200,\"timeout\":60},\"team\":\"research\"}");
        assertThat(base).contains("\"tokens\":100");
    }
}
