package io.agentteams.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TeamResourceFactory {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TeamResourceFactory() { }

    public static ConfigMap configMap(Team team) {
        if (team.getMetadata() == null || team.getMetadata().getName() == null
                || team.getMetadata().getName().isBlank()) {
            throw new IllegalArgumentException("Team metadata.name must not be blank");
        }
        String namespace = team.getMetadata().getNamespace() == null ? "default" : team.getMetadata().getNamespace();
        Map<String, String> labels = Map.of("app.kubernetes.io/name", "agentteams-team",
                "app.kubernetes.io/managed-by", "agentteams-operator");
        Map<String, String> data = new LinkedHashMap<>();
        try {
            data.put("team.json", MAPPER.writeValueAsString(team.getSpec()));
        } catch (Exception error) {
            throw new IllegalArgumentException("Team spec cannot be serialized", error);
        }
        ObjectMetaBuilder metadata = new ObjectMetaBuilder().withName(team.getMetadata().getName() + "-config")
                .withNamespace(namespace).withLabels(labels);
        if (team.getMetadata().getUid() != null && !team.getMetadata().getUid().isBlank()) {
            metadata.withOwnerReferences(new OwnerReferenceBuilder()
                    .withApiVersion("agentteams.io/v1alpha1").withKind("Team")
                    .withName(team.getMetadata().getName()).withUid(team.getMetadata().getUid())
                    .withController(true).withBlockOwnerDeletion(true).build());
        }
        return new ConfigMapBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withMetadata(metadata.build())
                .withData(data).build();
    }
}
