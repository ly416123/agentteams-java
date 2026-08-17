package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Optional;
import java.util.Set;
import jakarta.servlet.FilterChain;
import org.springframework.mock.web.MockFilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthenticationFilterTest {
    @Test
    void rejectsApiRequestsWithoutBearerToken() throws Exception {
        IdentityTokenValidator validator = token -> Optional.empty();
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void exposesAndCleansAuthenticatedPrincipal() throws Exception {
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project", "team"), Set.of("task:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> {
            assertThat(req.getAttribute(ApiAuthenticationFilter.PRINCIPAL_ATTRIBUTE)).isNotNull();
            assertThat(PrincipalContext.current()).isPresent();
            assertThat(PrincipalContext.actorOr("fallback")).isEqualTo("alice");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(PrincipalContext.current()).isEmpty();
    }
}
