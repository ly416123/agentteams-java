package io.agentteams.controlplane.agentspec;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dispatches AgentSpec references to the catalog adapter for their type.
 *
 * <p>This class deliberately contains no persistence access. The three ports are the wiring
 * points for the existing model, skill, and MCP directories.</p>
 */
public final class CompositeAgentSpecReferenceCatalog implements AgentSpecReferenceCatalog {

    private final Map<AgentSpecReferenceType, AgentSpecReferenceCatalogPort> ports;

    public CompositeAgentSpecReferenceCatalog(AgentSpecReferenceCatalogPort model,
            AgentSpecReferenceCatalogPort skill, AgentSpecReferenceCatalogPort mcp) {
        this(adapters(model, skill, mcp));
    }

    public CompositeAgentSpecReferenceCatalog(Iterable<? extends AgentSpecReferenceCatalogPort> ports) {
        EnumMap<AgentSpecReferenceType, AgentSpecReferenceCatalogPort> byType =
                new EnumMap<>(AgentSpecReferenceType.class);
        if (ports != null) {
            for (AgentSpecReferenceCatalogPort port : ports) {
                if (port == null) {
                    continue;
                }
                AgentSpecReferenceCatalogPort previous = byType.put(port.type(), port);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate AgentSpec catalog adapter: " + port.type());
                }
            }
        }
        this.ports = Map.copyOf(byType);
    }

    public CompositeAgentSpecReferenceCatalog(Map<AgentSpecReferenceType,
            ? extends AgentSpecReferenceCatalogPort> ports) {
        this(ports == null ? List.of() : ports.values());
    }

    @Override
    public Optional<ReferenceMetadata> find(AgentSpecReference reference) {
        return find(reference, Scope.unscoped());
    }

    @Override
    public Optional<ReferenceMetadata> find(AgentSpecReference reference, Scope scope) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(scope, "scope");
        AgentSpecReferenceCatalogPort port = ports.get(reference.type());
        return port == null ? Optional.empty() : nonNull(port.find(reference.value(), scope));
    }

    @Override
    public boolean isConfigured(AgentSpecReferenceType type) {
        return type != null && ports.containsKey(type);
    }

    public boolean hasAdapter(AgentSpecReferenceType type) {
        return isConfigured(type);
    }

    private static <T> Optional<T> nonNull(Optional<T> result) {
        return result == null ? Optional.empty() : result;
    }

    private static List<AgentSpecReferenceCatalogPort> adapters(AgentSpecReferenceCatalogPort model,
            AgentSpecReferenceCatalogPort skill, AgentSpecReferenceCatalogPort mcp) {
        List<AgentSpecReferenceCatalogPort> adapters = new ArrayList<>(3);
        if (model != null) adapters.add(model);
        if (skill != null) adapters.add(skill);
        if (mcp != null) adapters.add(mcp);
        return adapters;
    }
}
