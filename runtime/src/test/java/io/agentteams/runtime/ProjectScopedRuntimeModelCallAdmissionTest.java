package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectScopedRuntimeModelCallAdmissionTest {
    @Test
    void legacyUnscopedRequestDoesNotCallQuotaPort() {
        AtomicInteger calls = new AtomicInteger();
        ProjectScopedRuntimeModelCallAdmission admission = new ProjectScopedRuntimeModelCallAdmission(
                (tenant, project, tokens) -> {
                    calls.incrementAndGet();
                    return RuntimeQuotaLease.noop();
                });

        admission.acquire(new RuntimeModelCallAdmissionRequest("qwen", "qwen-plus", 100)).close();

        assertThat(calls).hasValue(0);
    }

    @Test
    void forwardsProjectScopeAndTokenEstimate() {
        String[] values = new String[3];
        ProjectScopedRuntimeModelCallAdmission admission = new ProjectScopedRuntimeModelCallAdmission(
                (tenant, project, tokens) -> {
                    values[0] = tenant;
                    values[1] = project;
                    values[2] = Long.toString(tokens);
                    return RuntimeQuotaLease.noop();
                });

        admission.acquire(new RuntimeModelCallAdmissionRequest("qwen", "qwen-plus", 321,
                "tenant-a", "project-a"));

        assertThat(values).containsExactly("tenant-a", "project-a", "321");
    }

    @Test
    void translatesQuotaRejectionAndReleasesLocalAdmission() {
        AtomicInteger releases = new AtomicInteger();
        RuntimeModelCallAdmission local = request -> RuntimeModelCallLease.idempotent(releases::incrementAndGet);
        ProjectScopedRuntimeModelCallAdmission admission = new ProjectScopedRuntimeModelCallAdmission(
                (tenant, project, tokens) -> { throw new RuntimeQuotaRejectedException("daily_tokens"); }, local);

        assertThatThrownBy(() -> admission.acquire(new RuntimeModelCallAdmissionRequest("qwen", "qwen-plus", 321,
                "tenant-a", "project-a")))
                .isInstanceOf(RuntimeModelCallAdmissionRejectedException.class)
                .hasMessage("project quota rejected model call: daily_tokens")
                .hasCauseInstanceOf(RuntimeQuotaRejectedException.class);
        assertThat(releases).hasValue(1);
    }

    @Test
    void releaseIsIdempotentAcrossRuntimeAndQuotaWrappers() {
        AtomicInteger releases = new AtomicInteger();
        ProjectScopedRuntimeModelCallAdmission admission = new ProjectScopedRuntimeModelCallAdmission(
                (tenant, project, tokens) -> RuntimeQuotaLease.idempotent(releases::incrementAndGet));

        RuntimeModelCallLease lease = admission.acquire(new RuntimeModelCallAdmissionRequest("qwen", "qwen-plus", 321,
                "tenant-a", "project-a"));
        lease.close();
        lease.close();

        assertThat(releases).hasValue(1);
    }

    @Test
    void compatibilityConstructorLeavesScopeUnset() {
        RuntimeModelCallAdmissionRequest request = new RuntimeModelCallAdmissionRequest("qwen", "qwen-plus", 100);

        assertThat(request.tenantId()).isNull();
        assertThat(request.projectId()).isNull();
        assertThat(request.hasProjectScope()).isFalse();
    }
}
