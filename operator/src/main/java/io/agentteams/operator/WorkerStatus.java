package io.agentteams.operator;

public class WorkerStatus {
    private String phase;
    private Long observedGeneration;
    private Integer readyReplicas;
    private String message;

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }
    public Integer getReadyReplicas() { return readyReplicas; }
    public void setReadyReplicas(Integer readyReplicas) { this.readyReplicas = readyReplicas; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
