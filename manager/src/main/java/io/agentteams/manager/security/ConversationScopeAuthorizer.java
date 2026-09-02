package io.agentteams.manager.security;

/** Authorizes Console conversation scope against the platform resource boundary. */
@FunctionalInterface
public interface ConversationScopeAuthorizer {
    void requireAccessible(String projectId, String teamId, ManagerPrincipal principal);

    /** Keeps standalone Manager unit tests and legacy external scopes self-contained. */
    static ConversationScopeAuthorizer legacy() {
        return (projectId, teamId, principal) -> {
            if (!principal.projectId().equals(projectId) || !principal.teamId().equals(teamId)) {
                throw new ManagerAuthorizationException("conversation scope does not match authenticated principal");
            }
        };
    }
}
