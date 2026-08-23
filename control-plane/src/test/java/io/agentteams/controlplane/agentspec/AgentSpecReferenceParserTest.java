package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentSpecReferenceParserTest {

    private final AgentSpecReferenceParser parser = new AgentSpecReferenceParser();

    @Test
    void parsesModelSkillAndMcpReferences() {
        AgentSpecReferences references = parser.parse("""
                {"modelRef":{"provider":"deepseek","model":"deepseek-chat"},
                 "skillRefs":["web-search-v1"],"mcpRefs":["search-mcp"]}
                """);

        assertThat(references.modelRef()).isEqualTo(
                new AgentSpecReferences.ModelRef("deepseek", "deepseek-chat"));
        assertThat(references.skillRefs()).containsExactly("web-search-v1");
        assertThat(references.mcpRefs()).containsExactly("search-mcp");
        assertThat(references.stream().toList()).containsExactly(
                new AgentSpecReference(AgentSpecReferenceType.MODEL, "deepseek/deepseek-chat"),
                new AgentSpecReference(AgentSpecReferenceType.SKILL, "web-search-v1"),
                new AgentSpecReference(AgentSpecReferenceType.MCP, "search-mcp"));
    }

    @Test
    void missingReferenceArraysAreEmptyAndMalformedModelRefIsRejected() {
        assertThat(parser.parse("{}")).isEqualTo(AgentSpecReferences.empty());
        assertThatThrownBy(() -> parser.parse("{\"modelRef\":{\"provider\":\"deepseek\"}}"))
                .hasMessage("spec.modelRef must be an object with provider and model strings");
        assertThat(new AgentSpecReferences(null, List.of(" skill "), List.of()).skillRefs())
                .containsExactly("skill");
    }
}
