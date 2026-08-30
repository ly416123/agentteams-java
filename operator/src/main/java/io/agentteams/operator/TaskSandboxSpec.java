package io.agentteams.operator;

import io.agentteams.application.api.SandboxProfile;
import java.util.Map;
import java.util.Objects;

public final class TaskSandboxSpec {
    private String taskId;
    private String attemptId;
    private String idempotencyKey;
    private SandboxProfile profile;
    private String runtimeClassName;
    private String image;
    private int ttlSeconds;
    private String template;
    private String expiresAt;
    private String terminationReason;
    private Map<String, String> resources;
    private boolean terminationRequested;

    public TaskSandboxSpec() {
        taskId = "";
        attemptId = "";
        idempotencyKey = null;
        profile = SandboxProfile.NONE;
        runtimeClassName = "";
        image = "";
        ttlSeconds = 1800;
        template = null;
        expiresAt = null;
        terminationReason = null;
        resources = Map.of();
        terminationRequested = false;
    }

    public TaskSandboxSpec(String taskId, String attemptId, SandboxProfile profile, String runtimeClassName,
            String image, int ttlSeconds, Map<String, String> resources) {
        setTaskId(taskId);
        setAttemptId(attemptId);
        setProfile(profile);
        setRuntimeClassName(runtimeClassName);
        setImage(image);
        setTtlSeconds(ttlSeconds);
        setResources(resources);
    }

    public String taskId() { return taskId; }
    public String attemptId() { return attemptId; }
    public String idempotencyKey() { return idempotencyKey; }
    public SandboxProfile profile() { return profile; }
    public String runtimeClassName() { return runtimeClassName; }
    public String image() { return image; }
    public int ttlSeconds() { return ttlSeconds; }
    public String template() { return template; }
    public String expiresAt() { return expiresAt; }
    public String terminationReason() { return terminationReason; }
    public Map<String, String> resources() { return resources; }
    public boolean terminationRequested() { return terminationRequested; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = required(value, "taskId"); }
    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String value) { attemptId = required(value, "attemptId"); }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = optional(value); }
    public SandboxProfile getProfile() { return profile; }
    public void setProfile(SandboxProfile value) { profile = Objects.requireNonNull(value, "profile"); }
    public String getRuntimeClassName() { return runtimeClassName; }
    public void setRuntimeClassName(String value) { runtimeClassName = value == null ? "" : value.trim(); }
    public String getImage() { return image; }
    public void setImage(String value) { image = required(value, "image"); }
    public int getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(int value) {
        if (value < 60 || value > 86400) {
            throw new IllegalArgumentException("ttlSeconds must be between 60 and 86400");
        }
        ttlSeconds = value;
    }
    public String getTemplate() { return template; }
    public void setTemplate(String value) { template = optional(value); }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String value) { expiresAt = optional(value); }
    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String value) { terminationReason = optional(value); }
    public Map<String, String> getResources() { return resources; }
    public void setResources(Map<String, String> value) {
        resources = Map.copyOf(Objects.requireNonNull(value, "resources"));
    }
    public boolean getTerminationRequested() { return terminationRequested; }
    public void setTerminationRequested(boolean value) { terminationRequested = value; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? null : value.trim();
    }
}
