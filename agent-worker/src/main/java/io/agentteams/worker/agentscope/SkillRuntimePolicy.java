package io.agentteams.worker.agentscope;

import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SkillCapabilityPolicy;
import io.agentteams.runtime.RuntimeMcpServer;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed MCP view of the active Skill capability policies.
 *
 * <p>The current Worker exposes all activated Skills through one AgentScope
 * toolkit and therefore has no per-Skill tool-call context. Until that
 * context exists, the safe combined policy is the intersection of every
 * active Skill policy. An empty policy collection is the compatibility mode
 * for legacy manifests that carry no Skill capability declarations.</p>
 */
final class SkillRuntimePolicy {
    private final boolean constrained;
    private final Set<String> allowedMcp;
    private final Set<String> allowedDomains;
    private final Set<String> allowedTools;
    private final boolean allowSecretReferences;
    private final SandboxPolicy.NetworkPolicy networkPolicy;

    private SkillRuntimePolicy(boolean constrained, Set<String> allowedMcp, Set<String> allowedDomains,
            Set<String> allowedTools, boolean allowSecretReferences, SandboxPolicy.NetworkPolicy networkPolicy) {
        this.constrained = constrained;
        this.allowedMcp = Set.copyOf(allowedMcp);
        this.allowedDomains = Set.copyOf(allowedDomains);
        this.allowedTools = Set.copyOf(allowedTools);
        this.allowSecretReferences = allowSecretReferences;
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
    }

    static SkillRuntimePolicy from(Collection<SkillCapabilityPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        List<SkillCapabilityPolicy> values = policies.stream()
                .map(policy -> Objects.requireNonNull(policy, "skill capability policy"))
                .toList();
        if (values.isEmpty()) {
            return new SkillRuntimePolicy(false, Set.of(), Set.of(), Set.of(), true,
                    SandboxPolicy.NetworkPolicy.OPEN);
        }
        Set<String> mcp = new LinkedHashSet<>(values.get(0).allowedMcp());
        Set<String> domains = new LinkedHashSet<>(values.get(0).allowedDomains());
        Set<String> tools = new LinkedHashSet<>(values.get(0).allowedTools());
        SandboxPolicy.NetworkPolicy network = values.get(0).networkPolicy();
        boolean secrets = values.get(0).allowSecretReferences();
        for (SkillCapabilityPolicy policy : values.subList(1, values.size())) {
            mcp.retainAll(policy.allowedMcp());
            domains.retainAll(policy.allowedDomains());
            tools.retainAll(policy.allowedTools());
            if (policy.networkPolicy().ordinal() < network.ordinal()) network = policy.networkPolicy();
            secrets &= policy.allowSecretReferences();
        }
        return new SkillRuntimePolicy(true, mcp, domains, tools, secrets, network);
    }

    boolean constrained() {
        return constrained;
    }

    List<String> allowedTools() {
        return allowedTools.stream().sorted().toList();
    }

    boolean allows(RuntimeMcpServer server) {
        Objects.requireNonNull(server, "server");
        if (!constrained) return true;
        if (networkPolicy == SandboxPolicy.NetworkPolicy.DENY_ALL
                || !allowedMcp.contains(server.reference())
                || allowedTools.isEmpty()) return false;
        if (server.credentialRef() != null && !allowSecretReferences) return false;
        URI endpoint = URI.create(server.endpoint());
        return endpoint.getHost() != null && allowedDomains.contains(endpoint.getHost().toLowerCase(java.util.Locale.ROOT));
    }
}
