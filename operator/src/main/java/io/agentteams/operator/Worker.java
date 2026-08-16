package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("agentteams.io")
@Version("v1alpha1")
@Kind("Worker")
@Plural("workers")
public class Worker extends CustomResource<WorkerSpec, WorkerStatus> implements Namespaced {
    @Override
    protected WorkerSpec initSpec() {
        return null;
    }

    @Override
    protected WorkerStatus initStatus() {
        return new WorkerStatus();
    }
}
