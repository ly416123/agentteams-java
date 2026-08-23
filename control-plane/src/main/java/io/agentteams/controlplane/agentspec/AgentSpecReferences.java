package io.agentteams.controlplane.agentspec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** The normalized reference portion of an AgentSpec document. */
public record AgentSpecReferences(
        ModelRef modelRef,
        List<String> skillRefs,
        List<String> mcpRefs) {

    public AgentSpecReferences {
        skillRefs = immutableRefs(skillRefs, "skillRefs");
        mcpRefs = immutableRefs(mcpRefs, "mcpRefs");
    }

    public static AgentSpecReferences empty() {
        return new AgentSpecReferences(null, List.of(), List.of());
    }

    public AgentSpecReferences withModelRef(ModelRef fallback) {
        return modelRef == null && fallback != null
                ? new AgentSpecReferences(fallback, skillRefs, mcpRefs) : this;
    }

    public Stream<AgentSpecReference> stream() {
        Stream<AgentSpecReference> model = modelRef == null
                ? Stream.empty()
                : Stream.of(new AgentSpecReference(AgentSpecReferenceType.MODEL,
                        modelRef.provider() + "/" + modelRef.model()));
        return Stream.concat(Stream.concat(model,
                        skillRefs.stream().map(ref -> new AgentSpecReference(
                                AgentSpecReferenceType.SKILL, ref))),
                mcpRefs.stream().map(ref -> new AgentSpecReference(
                        AgentSpecReferenceType.MCP, ref)));
    }

    public record ModelRef(String provider, String model) {
        public ModelRef {
            requireText(provider, "modelRef.provider");
            requireText(model, "modelRef.model");
            provider = provider.trim();
            model = model.trim();
        }

        public String value() {
            return provider + "/" + model;
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    private static List<String> immutableRefs(List<String> refs, String field) {
        Objects.requireNonNull(refs, field);
        List<String> copy = new ArrayList<>(refs.size());
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank references");
            }
            copy.add(ref.trim());
        }
        return List.copyOf(copy);
    }
}
