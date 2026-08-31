package io.agentteams.controlplane.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical ordering and conflict checks for Team resource pins. */
public final class TeamResourceBindings {
    private TeamResourceBindings() { }

    public static List<TeamResourceBinding> canonicalize(List<TeamResourceBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        Map<Key, TeamResourceBinding> unique = new LinkedHashMap<>();
        for (TeamResourceBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            Key key = new Key(binding.type(), binding.resourceId(), binding.resourceRevision());
            TeamResourceBinding previous = unique.putIfAbsent(key, binding);
            if (previous != null && !previous.digest().equals(binding.digest())) {
                throw new IllegalArgumentException("conflicting Team resource binding digest");
            }
        }
        return unique.values().stream().sorted((left, right) -> {
            int type = left.type().compareTo(right.type());
            if (type != 0) return type;
            int id = left.resourceId().toString().compareTo(right.resourceId().toString());
            if (id != 0) return id;
            int revision = left.resourceRevision().compareTo(right.resourceRevision());
            if (revision != 0) return revision;
            return left.digest().compareTo(right.digest());
        }).toList();
    }

    public static String canonicalText(List<TeamResourceBinding> bindings) {
        List<TeamResourceBinding> canonical = canonicalize(bindings);
        List<String> values = new ArrayList<>(canonical.size());
        for (TeamResourceBinding binding : canonical) {
            values.add(binding.type().name() + "\u0000" + binding.resourceId() + "\u0000"
                    + binding.resourceRevision() + "\u0000" + binding.digest());
        }
        return String.join("\n", values);
    }

    private record Key(TeamResourceType type, java.util.UUID resourceId, String revision) { }
}
