package io.agentteams.controlplane.artifact;

import java.util.UUID;

public final class ObjectStoragePaths {
    private ObjectStoragePaths() { }

    public static String taskSpec(UUID taskId) {
        return "tasks/" + require(taskId) + "/spec.json";
    }

    public static String result(UUID taskId, UUID attemptId) {
        return "tasks/" + require(taskId) + "/attempts/" + require(attemptId) + "/result.json";
    }

    public static String artifact(UUID taskId, UUID attemptId, String name) {
        if (name == null || name.isBlank() || name.contains("..") || name.startsWith("/")) {
            throw new IllegalArgumentException("artifact name is unsafe");
        }
        return "tasks/" + require(taskId) + "/attempts/" + require(attemptId) + "/artifacts/" + name;
    }

    private static UUID require(UUID id) { return java.util.Objects.requireNonNull(id, "id"); }
}
