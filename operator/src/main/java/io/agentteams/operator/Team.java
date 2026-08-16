package io.agentteams.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("agentteams.io")
@Version("v1alpha1")
@Kind("Team")
@Plural("teams")
public class Team extends CustomResource<TeamSpec, TeamStatus> implements Namespaced {
    @Override
    protected TeamSpec initSpec() { return null; }

    @Override
    protected TeamStatus initStatus() { return new TeamStatus(); }
}
