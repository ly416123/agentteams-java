package io.agentteams.operator;

public class TeamStatus {
    private String phase;
    private Long observedGeneration;
    private String message;

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
