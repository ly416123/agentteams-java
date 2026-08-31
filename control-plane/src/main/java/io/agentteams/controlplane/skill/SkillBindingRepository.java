package io.agentteams.controlplane.skill;

import java.util.List;

public interface SkillBindingRepository {
    SkillBindingRecord bind(SkillBindingRecord record);

    List<SkillBindingRecord> find(String organizationId, String tenantId, String projectId, String teamId);
}
