package io.agentteams.controlplane.agentspec;

import java.util.List;
import java.util.Objects;

/** Stable, transport-neutral result for AgentSpec reference validation. */
public record AgentSpecReferenceValidationResult(
        Category category,
        List<Violation> violations,
        List<AgentSpecReferenceBinding> bindings) {

    /** Compatibility constructor for callers that only consume validation categories. */
    public AgentSpecReferenceValidationResult(Category category, List<Violation> violations) {
        this(category, violations, List.of());
    }

    public AgentSpecReferenceValidationResult {
        Objects.requireNonNull(category, "category");
        violations = violations == null ? List.of() : List.copyOf(violations);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        if (category == Category.VALID && !violations.isEmpty()) {
            throw new IllegalArgumentException("VALID result must not contain violations");
        }
        if (category != Category.VALID && violations.isEmpty()) {
            throw new IllegalArgumentException("invalid result must contain a violation");
        }
    }

    public static AgentSpecReferenceValidationResult valid() {
        return valid(List.of());
    }

    public static AgentSpecReferenceValidationResult valid(List<AgentSpecReferenceBinding> bindings) {
        return new AgentSpecReferenceValidationResult(Category.VALID, List.of(), bindings);
    }

    public boolean isValid() {
        return category == Category.VALID;
    }

    public String code() {
        return category.name();
    }

    public enum Category {
        VALID,
        REFERENCE_NOT_FOUND,
        PROJECT_NOT_VISIBLE,
        LIFECYCLE_NOT_AVAILABLE,
        BINDING_METADATA_INCOMPLETE
    }

    public record Violation(AgentSpecReference reference, Category category, String detail) {
        public Violation {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(category, "category");
            if (category == Category.VALID) {
                throw new IllegalArgumentException("violation category must be invalid");
            }
        }
    }
}
