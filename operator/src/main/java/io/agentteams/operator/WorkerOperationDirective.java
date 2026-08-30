package io.agentteams.operator;

/** A durable lifecycle command read by the Kubernetes Operator. */
public record WorkerOperationDirective(
        String id,
        String agentId,
        String type,
        String requestedSpecDigest,
        String requestedRuntime,
        String requestedConfigRevision,
        String requestedSecretGeneration,
        long version) {

    public WorkerOperationDirective(String id, String agentId, String type,
            String requestedSpecDigest, String requestedRuntime,
            String requestedConfigRevision, String requestedSecretGeneration) {
        this(id, agentId, type, requestedSpecDigest, requestedRuntime,
                requestedConfigRevision, requestedSecretGeneration, 0);
    }

    public WorkerOperationDirective {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        requestedSpecDigest = optionalText(requestedSpecDigest);
        requestedRuntime = optionalText(requestedRuntime);
        requestedConfigRevision = optionalText(requestedConfigRevision);
        requestedSecretGeneration = optionalText(requestedSecretGeneration);
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
