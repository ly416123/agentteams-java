package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SkillPackageValidatorTest {

    private static final String DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final SkillPackageValidator validator = new SkillPackageValidator(new ObjectMapper(), 1024);

    @Test
    void acceptsValidSemverDigestManifestAndPackageSize() {
        validator.validate("1.2.3", DIGEST,
                "{\"name\":\"code-review\",\"description\":\"Reviews code\","
                        + "\"entry\":\"SKILL.md\",\"sizeBytes\":1024}");
    }

    @Test
    void reportsMissingManifestField() {
        assertThatThrownBy(() -> validator.validate("1.2.3", DIGEST,
                "{\"name\":\"code-review\",\"entry\":\"SKILL.md\",\"sizeBytes\":1}"))
                .isInstanceOf(SkillPackageValidationException.class)
                .hasMessage("manifest.description is required and must be a non-blank string");
    }

    @Test
    void rejectsInvalidVersionAndDigest() {
        assertThatThrownBy(() -> validator.validate("1.2", DIGEST,
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":1}"))
                .hasMessageContaining("version must use semantic version format");
        assertThatThrownBy(() -> validator.validate("1.2.3", "sha256:abc",
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":1}"))
                .hasMessage("digest must use sha256:<64 hexadecimal characters> format");
    }

    @Test
    void rejectsUnsafeEntryAndOversizedPackage() {
        assertThatThrownBy(() -> validator.validate("1.2.3", DIGEST,
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"../SKILL.md\",\"sizeBytes\":1}"))
                .hasMessage("manifest.entry must be a safe relative path");
        assertThatThrownBy(() -> validator.validate("1.2.3", DIGEST,
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":1025}"))
                .hasMessage("manifest.sizeBytes exceeds the maximum package size of 1024 bytes");
    }
}
