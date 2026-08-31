package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillCapabilityPolicyTest {

    @Test
    void acceptsCapabilitiesWithinTheEffectiveSandboxPolicy() {
        SkillCapabilityPolicy skill = new SkillCapabilityPolicy(SandboxProfile.ISOLATED, 500, 512, 1_024,
                Duration.ofMinutes(10), Set.of("github"), Set.of("api.github.com"), false);
        SandboxPolicy effective = new SandboxPolicy(SandboxProfile.HARDENED, "kata",
                ExecutionPlacement.PRIVATE_DEPLOYMENT, 1_000, 1_024, 2_048, Duration.ofMinutes(30),
                SandboxPolicy.NetworkPolicy.RESTRICTED, Set.of("github"), Set.of("api.github.com"), false, null);

        assertDoesNotThrow(() -> skill.requireAllowedBy(effective));
    }

    @Test
    void rejectsCapabilitiesThatWouldWidenTheEffectivePolicy() {
        SkillCapabilityPolicy skill = new SkillCapabilityPolicy(SandboxProfile.ISOLATED, 2_000, 512, 1_024,
                Duration.ofMinutes(10), Set.of("slack"), Set.of("evil.example"), true);
        SandboxPolicy effective = SandboxPolicy.defaults();

        assertThrows(IllegalArgumentException.class, () -> skill.requireAllowedBy(effective));
    }

    @Test
    void rejectsNetworkAccessThatWouldWidenTheEffectivePolicy() {
        SkillCapabilityPolicy skill = new SkillCapabilityPolicy(SandboxProfile.NONE, 250, 256, 512,
                Duration.ofMinutes(10), Set.of(), Set.of(), false, SandboxPolicy.NetworkPolicy.OPEN);
        SandboxPolicy effective = SandboxPolicy.defaults();

        assertThrows(IllegalArgumentException.class, () -> skill.requireAllowedBy(effective));
    }
}
