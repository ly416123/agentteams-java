package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract matrix for the common tenant/project/team boundary.
 *
 * <p>All resource services delegate the final scope comparison to
 * {@link AuthorizationService}; keeping the three axes in one parameterized
 * contract prevents a new resource adapter from accidentally checking only
 * tenant or project while ignoring team ownership.</p>
 */
class ScopeIsolationMatrixTest {
    private static final AuthorizationService AUTHORIZATION = new AuthorizationService();
    private static final AuthorizationService.Scope OWNER =
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a");

    static Stream<Arguments> resourceKinds() {
        return Stream.of("WORKER", "TEAM", "MODEL_PROVIDER", "MODEL", "SKILL", "MCP_SERVER",
                "TASK", "ARTIFACT", "USAGE", "AUDIT")
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} rejects tenant/project/team scope drift")
    @MethodSource("resourceKinds")
    void rejectsAnyScopeAxisDrift(String resourceType) {
        Set<AuthorizationService.Scope> invalidScopes = Set.of(
                new AuthorizationService.Scope("tenant-b", "project-a", "team-a"),
                new AuthorizationService.Scope("tenant-a", "project-b", "team-a"),
                new AuthorizationService.Scope("tenant-a", "project-a", "team-b"));

        for (AuthorizationService.Scope invalid : invalidScopes) {
            assertThatThrownBy(() -> AUTHORIZATION.requireScope(OWNER, invalid))
                    .as("resource type %s must preserve all scope axes", resourceType)
                    .isInstanceOf(AuthorizationException.class)
                    .hasMessage("resource is outside the caller scope");
        }
    }

    @ParameterizedTest(name = "{0} accepts an exact owner scope")
    @MethodSource("resourceKinds")
    void acceptsExactScope(String resourceType) {
        AUTHORIZATION.requireScope(OWNER,
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"));
    }
}
