package io.agentteams.manager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ManagerAuthenticationFilterTest {
    @AfterEach
    void clearContext() { ManagerRequestContext.clear(); }

    @Test
    void rejectsMissingBearerTokenBeforeCallingApplication() throws Exception {
        ManagerIdentityTokenValidator validator = mock(ManagerIdentityTokenValidator.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/manager/sessions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ManagerAuthenticationFilter(validator).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
        verify(chain, org.mockito.Mockito.never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void installsVerifiedPrincipalOnlyForValidBearerToken() throws Exception {
        ManagerIdentityTokenValidator validator = mock(ManagerIdentityTokenValidator.class);
        ManagerPrincipal principal = new ManagerPrincipal("user", "tenant", "project", "team",
                Set.of("task:create"));
        when(validator.validate("signed-token")).thenReturn(Optional.of(principal));
        FilterChain chain = (request, response) -> {
            assertThat(ManagerRequestContext.require()).isEqualTo(principal);
            assertThat(ManagerRequestContext.requireBearerToken()).isEqualTo("signed-token");
        };
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/manager/sessions");
        request.addHeader("Authorization", "Bearer signed-token");

        new ManagerAuthenticationFilter(validator).doFilter(request, new MockHttpServletResponse(), chain);

        assertThatThrownBy(ManagerRequestContext::require).isInstanceOf(ManagerAuthenticationException.class);
    }
}
