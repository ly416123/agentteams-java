package io.agentteams.operator;

public final class TaskSandboxStatus {
    private String phase;
    private String providerSandboxId;
    private String endpointRef;
    private Long observedGeneration;
    private String workloadUid;
    private String failureCategory;
    private String message;

    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }
    public String getProviderSandboxId() { return providerSandboxId; }
    public void setProviderSandboxId(String value) { providerSandboxId = value; }
    public String getEndpointRef() { return endpointRef; }
    public void setEndpointRef(String value) { endpointRef = value; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long value) { observedGeneration = value; }
    public String getWorkloadUid() { return workloadUid; }
    public void setWorkloadUid(String value) { workloadUid = value; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String value) { failureCategory = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
}
