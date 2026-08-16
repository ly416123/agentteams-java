package io.agentteams.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
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
        return new ConfigMapBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withMetadata(new ObjectMetaBuilder().withName(team.getMetadata().getName() + "-config")
                        .withNamespace(namespace).withLabels(labels).build())
                .withData(data).build();
    }
}
