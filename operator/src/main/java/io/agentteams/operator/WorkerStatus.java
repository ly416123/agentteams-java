package io.agentteams.operator;

public class WorkerStatus {
    private String phase;
    private Long observedGeneration;
    private Integer readyReplicas;
    private String message;
    private String observedSpecDigest;
    private String observedRuntime;
    private String observedConfigRevision;
    private String observedSecretGeneration;

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }
    public Integer getReadyReplicas() { return readyReplicas; }
    public void setReadyReplicas(Integer readyReplicas) { this.readyReplicas = readyReplicas; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getObservedSpecDigest() { return observedSpecDigest; }
    public void setObservedSpecDigest(String observedSpecDigest) { this.observedSpecDigest = observedSpecDigest; }
    public String getObservedRuntime() { return observedRuntime; }
    public void setObservedRuntime(String observedRuntime) { this.observedRuntime = observedRuntime; }
    public String getObservedConfigRevision() { return observedConfigRevision; }
    public void setObservedConfigRevision(String observedConfigRevision) { this.observedConfigRevision = observedConfigRevision; }
    public String getObservedSecretGeneration() { return observedSecretGeneration; }
    public void setObservedSecretGeneration(String observedSecretGeneration) {
        this.observedSecretGeneration = observedSecretGeneration;
    }
}
