package io.agentteams.controlplane.skill;

import java.util.Objects;
import java.util.UUID;

/** Server-owned object keys for skill packages. No caller-supplied path is accepted. */
public final class SkillPackageStoragePaths {
    private SkillPackageStoragePaths() {
    }

    public static String versionPackage(UUID skillId, UUID versionId) {
        return "skills/" + require(skillId) + "/versions/" + require(versionId) + "/package.tar.gz";
    }

    public static boolean isVersionPackage(UUID skillId, UUID versionId, String storageKey) {
        return versionPackage(skillId, versionId).equals(storageKey);
    }

    private static UUID require(UUID value) {
        return Objects.requireNonNull(value, "id");
    }
}
