package io.agentteams.controlplane.team;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TeamSchedulingPolicy {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Decision evaluate(TeamPolicyRecord policy, TeamMemberRecord member, AgentRecord agent,
            AssignmentRequest request, int activeAssignments) {
        if (member == null || !"ACTIVE".equals(member.status())) return Decision.reject("MEMBER_INACTIVE");
        if (!agent.id().equals(member.agentId())) return Decision.reject("MEMBER_AGENT_MISMATCH");
        if (agent.phase() != io.agentteams.domain.agent.AgentPhase.READY) return Decision.reject("AGENT_NOT_READY");
        if (activeAssignments >= policy.maxConcurrentTasks()) return Decision.reject("TEAM_CONCURRENCY_LIMIT");
        if (policy.requireHumanApproval() && !request.approvalGranted()) return Decision.reject("APPROVAL_REQUIRED");
        if (!policy.allowedRuntimes().isEmpty() && !policy.allowedRuntimes().contains(agent.runtime())) {
            return Decision.reject("RUNTIME_NOT_ALLOWED");
        }
        Set<String> capabilities = capabilities(agent.capabilitiesJson());
        Set<String> required = new HashSet<>(policy.requiredCapabilities());
        required.addAll(request.requiredCapabilities());
        if (!capabilities.containsAll(required)) return Decision.reject("CAPABILITY_MISMATCH");
        return Decision.allow();
    }

    private Set<String> capabilities(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            Set<String> result = new HashSet<>();
            node.fieldNames().forEachRemaining(result::add);
            return result;
        } catch (Exception error) {
            throw new IllegalArgumentException("agent capabilities must be a JSON object", error);
        }
    }

    public record AssignmentRequest(UUID taskId, List<String> requiredCapabilities,
            boolean approvalGranted) {
        public AssignmentRequest {
            if (taskId == null) throw new IllegalArgumentException("taskId must not be null");
            requiredCapabilities = List.copyOf(requiredCapabilities == null ? List.of() : requiredCapabilities);
        }
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, "OK"); }
        static Decision reject(String reason) { return new Decision(false, reason); }
    }
}
