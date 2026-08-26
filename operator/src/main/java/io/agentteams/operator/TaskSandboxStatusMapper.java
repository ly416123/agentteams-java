package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.Map;

/** Maps only Kubernetes control-plane state into the small TaskSandbox status projection. */
public final class TaskSandboxStatusMapper {

    private TaskSandboxStatusMapper() {
    }

    public static TaskSandboxStatus map(TaskSandbox sandbox, Job job, Service service, Endpoints endpoints,
            boolean runnerHealthy) {
        TaskSandboxStatus previous = sandbox.getStatus();
        Long currentGeneration = sandbox.getMetadata() == null ? null : sandbox.getMetadata().getGeneration();
        long generation = currentGeneration == null ? 0L : currentGeneration;
        boolean currentJob = job == null || isCurrentGeneration(job, generation, previous);

        TaskSandboxStatus status = new TaskSandboxStatus();
        status.setProviderSandboxId(providerId(sandbox, previous));
        status.setWorkloadUid(job == null || job.getMetadata() == null ? null : job.getMetadata().getUid());
        status.setEndpointRef(service == null || service.getMetadata() == null
                ? null : service.getMetadata().getName());
        if (previous != null && previous.getObservedGeneration() != null && !currentJob) {
            status.setObservedGeneration(previous.getObservedGeneration());
        } else if (job != null && currentJob) {
            status.setObservedGeneration(generation);
        } else if (previous != null) {
            status.setObservedGeneration(previous.getObservedGeneration());
        }

        if (job == null) {
            if (previous != null && "FAILED".equals(previous.getPhase())) {
                status.setPhase("FAILED");
                status.setFailureCategory(previous.getFailureCategory());
                status.setMessage(previous.getMessage());
                return status;
            }
            if (previous != null && "READY".equals(previous.getPhase())
                    && generationMatches(previous, generation)) {
                status.setPhase("LOST");
                status.setMessage("Sandbox workload disappeared after becoming ready");
            } else {
                status.setPhase("PROVISIONING");
                status.setMessage("Waiting for Sandbox Job to be created");
            }
            return status;
        }
        if (!currentJob) {
            status.setPhase("PROVISIONING");
            status.setMessage("Waiting for the current Sandbox generation");
            return status;
        }

        String failureReason = failureReason(job);
        if (failureReason != null) {
            status.setPhase("FAILED");
            status.setFailureCategory("PROVIDER_RESPONSE_INVALID");
            status.setMessage("Sandbox Job failed: " + failureReason);
            return status;
        }
        if (active(job) && hasEndpoint(service, endpoints) && runnerHealthy) {
            status.setPhase("READY");
            status.setMessage("Sandbox runner is ready");
        } else {
            status.setPhase("PROVISIONING");
            status.setMessage("Waiting for Sandbox runner readiness");
        }
        return status;
    }

    private static boolean isCurrentGeneration(Job job, long generation, TaskSandboxStatus previous) {
        Map<String, String> labels = job.getMetadata() == null ? null : job.getMetadata().getLabels();
        String value = labels == null ? null : labels.get(TaskSandboxResourceFactory.GENERATION_LABEL);
        return value != null && String.valueOf(generation).equals(value);
    }

    private static boolean generationMatches(TaskSandboxStatus status, long generation) {
        return status.getObservedGeneration() == null || status.getObservedGeneration() == generation;
    }

    private static String providerId(TaskSandbox sandbox, TaskSandboxStatus previous) {
        if (sandbox.getMetadata() != null && sandbox.getMetadata().getName() != null
                && !sandbox.getMetadata().getName().isBlank()) {
            return sandbox.getMetadata().getName();
        }
        return previous == null ? null : previous.getProviderSandboxId();
    }

    private static boolean active(Job job) {
        return job.getStatus() != null && job.getStatus().getActive() != null
                && job.getStatus().getActive() > 0;
    }

    private static String failureReason(Job job) {
        if (job.getStatus() == null) return null;
        if (job.getStatus().getConditions() != null) {
            for (JobCondition condition : job.getStatus().getConditions()) {
                if ("Failed".equals(condition.getType())) {
                    if (condition.getReason() != null && !condition.getReason().isBlank()) {
                        return condition.getReason();
                    }
                    return "JobFailed";
                }
            }
        }
        if (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) {
            return "JobFailed";
        }
        return null;
    }

    private static boolean hasEndpoint(Service service, Endpoints endpoints) {
        return service != null && service.getSpec() != null
                && "ClusterIP".equals(service.getSpec().getType())
                && endpoints != null && endpoints.getSubsets() != null
                && endpoints.getSubsets().stream().anyMatch(subset -> subset.getAddresses() != null
                        && !subset.getAddresses().isEmpty());
    }
}
