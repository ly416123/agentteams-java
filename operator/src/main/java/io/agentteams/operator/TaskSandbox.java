package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("agentteams.io")
@Version("v1alpha1")
@Kind("TaskSandbox")
@Plural("tasksandboxes")
public class TaskSandbox extends CustomResource<TaskSandboxSpec, TaskSandboxStatus> implements Namespaced {
    @Override
    protected TaskSandboxSpec initSpec() {
        return new TaskSandboxSpec();
    }

    @Override
    protected TaskSandboxStatus initStatus() {
        return new TaskSandboxStatus();
    }
}
