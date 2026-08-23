package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillScanApprovalPortTest {

    @Test
    void safeDefaultIsPendingAndCannotApproveAReviewRequiredScan() {
        SkillScanApprovalPort port = new SafeDefaultSkillScanApprovalPort();

        assertThat(port.onReviewRequired(new SkillScanApprovalPort.ApprovalRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SANDBOX_TIMEOUT", "sha256:digest")))
                .isEqualTo(SkillScanApprovalPort.ApprovalStatus.PENDING);
        assertThat(SkillScanApprovalPort.safeDefault().onReviewRequired(new SkillScanApprovalPort.ApprovalRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SANDBOX_TIMEOUT", "sha256:digest")))
                .isEqualTo(SkillScanApprovalPort.ApprovalStatus.PENDING);
    }

    @Test
    void approvalRequestContainsOnlyBoundedMetadata() {
        assertThatThrownBy(() -> new SkillScanApprovalPort.ApprovalRequest(
                UUID.randomUUID(), UUID.randomUUID(), "x".repeat(121), "sha256:digest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("classification must be at most 120 characters");
    }
}
