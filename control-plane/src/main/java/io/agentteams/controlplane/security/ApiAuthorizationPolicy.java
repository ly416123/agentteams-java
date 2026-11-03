package io.agentteams.controlplane.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Maps the public HTTP API to the permission required after OIDC authentication. */
public final class ApiAuthorizationPolicy {
    private ApiAuthorizationPolicy() { }

    public static boolean hasExplicitAuthorizationBoundary(HttpServletRequest request) {
        String path = request.getRequestURI();
        return requiredPermission(request).isPresent()
                || isDatabaseAuthorizedProjectRoute(request)
                || isDatabaseAuthorizedManagementRoute(request);
    }

    private static boolean isDatabaseAuthorizedProjectRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && (path.equals("/api/v1/projects")
                || path.matches("/api/v1/projects/[0-9a-fA-F-]{36}/(members|authorization/roles|role)"))) return true;
        if ("POST".equals(method) && (path.equals("/api/v1/projects")
                || path.matches("/api/v1/projects/[0-9a-fA-F-]{36}/(members|invitations|invitations/accept|owner/transfer)")
                || path.matches("/api/v1/projects/[0-9a-fA-F-]{36}/members/[^/]+/(enable|role)"))) return true;
        return "DELETE".equals(method)
                && path.matches("/api/v1/projects/[0-9a-fA-F-]{36}/members/[^/]+");
    }

    private static boolean isDatabaseAuthorizedManagementRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && (path.equals("/api/v1/management/organizations")
                || path.equals("/api/v1/management/users")
                || path.matches("/api/v1/management/organizations/[0-9a-fA-F-]{36}/(tenants|integrations|memberships)")
                || path.matches("/api/v1/management/integrations/[0-9a-fA-F-]{36}/(credentials|external-identities|provisioned-users)")
                || path.matches("/api/v1/management/integrations/[0-9a-fA-F-]{36}/provisioned-users/[^/]+/memberships")
                || path.matches("/api/v1/management/authorizations/(me|permissions|assignments)"))) return true;
        if ("POST".equals(method) && (path.matches("/api/v1/management/(organizations|users|platform/admins)")
                || path.matches("/api/v1/management/organizations/[0-9a-fA-F-]{36}/(tenants|integrations|memberships|status)")
                || path.matches("/api/v1/management/tenants/[0-9a-fA-F-]{36}/status")
                || path.matches("/api/v1/management/integrations/[0-9a-fA-F-]{36}/(credentials|external-identities|provisioned-users)")
                || path.matches("/api/v1/management/integrations/[0-9a-fA-F-]{36}/provisioned-users/[^/]+/disable")
                || path.matches("/api/v1/management/credentials/[0-9a-fA-F-]{36}/(rotate|revoke)")
                || path.matches("/api/v1/management/users/[0-9a-fA-F-]{36}/status")
                || path.matches("/api/v1/management/authorizations/(organizations/[0-9a-fA-F-]{36}/admins|platform/admins)")
                || path.matches("/api/v1/management/authorizations/(organizations/[0-9a-fA-F-]{36}/admins|platform/admins)/[^/]+/revoke"))) return true;
        return "PUT".equals(method)
                && path.matches("/api/v1/management/integrations/[0-9a-fA-F-]{36}/(provisioning-policy|provisioned-users/[^/]+)");
    }

    public static Optional<Permission> requiredPermission(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // Project roles are resolved from control-plane membership, not Keycloak permissions.
        if (path.startsWith("/api/v1/projects")) {
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/management/tasks/")) {
            if (path.matches("/api/v1/management/tasks/[0-9a-fA-F-]{36}/(cancel|retry)")) {
                return Optional.of(path.endsWith("/cancel") ? Permission.TASK_CANCEL : Permission.TASK_RETRY);
            }
            if (path.matches("/api/v1/management/tasks/[0-9a-fA-F-]{36}/(queue|pause)")) {
                return Optional.of(Permission.TASK_PAUSE);
            }
            if (path.matches("/api/v1/management/tasks/[0-9a-fA-F-]{36}/approve")) {
                return Optional.of(Permission.TASK_APPROVE);
            }
            if (path.matches("/api/v1/management/tasks/[0-9a-fA-F-]{36}/reject")) {
                return Optional.of(Permission.TASK_REJECT);
            }
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/management/")) {
            // Management endpoints perform database-backed role and resource-scope checks in their service layer.
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/teams")) {
            return Optional.of("GET".equals(method) ? Permission.TEAM_READ : Permission.TEAM_WRITE);
        }
        if (path.startsWith("/api/v1/agents")) {
            return Optional.of("GET".equals(method) ? Permission.AGENT_READ : Permission.AGENT_WRITE);
        }
        if (path.startsWith("/api/v1/tasks/") && path.contains("/artifacts")) {
            return Optional.of("GET".equals(method) ? Permission.ARTIFACT_READ : Permission.ARTIFACT_WRITE);
        }
        if (path.startsWith("/api/v1/artifacts/retention")) {
            return Optional.of("GET".equals(method) ? Permission.ARTIFACT_READ : Permission.ARTIFACT_WRITE);
        }
        if (path.startsWith("/api/v1/artifacts")) {
            return Optional.of("GET".equals(method) ? Permission.ARTIFACT_READ : Permission.ARTIFACT_WRITE);
        }
        if (path.startsWith("/api/v1/tasks")) {
            if ("GET".equals(method)) return Optional.of(Permission.TASK_READ);
            if (path.endsWith("/review")) return Optional.of(Permission.TASK_APPROVE);
            if ("DELETE".equals(method)) return Optional.of(Permission.TASK_CANCEL);
            if ("PATCH".equals(method) || path.endsWith("/queue") || path.endsWith("/archive")
                    || path.endsWith("/unarchive") || path.contains("/adjustments")) {
                return Optional.of(Permission.TASK_PAUSE);
            }
            if (path.endsWith("/cancel")) return Optional.of(Permission.TASK_CANCEL);
            if (path.endsWith("/retry")) return Optional.of(Permission.TASK_RETRY);
            if (path.endsWith("/pause")) return Optional.of(Permission.TASK_PAUSE);
            if (path.endsWith("/approve")) return Optional.of(Permission.TASK_APPROVE);
            if (path.endsWith("/reject")) return Optional.of(Permission.TASK_REJECT);
            return Optional.of(Permission.TASK_CREATE);
        }
        if (path.startsWith("/api/v1/config")) {
            return Optional.of("GET".equals(method) ? Permission.CONFIG_READ : Permission.CONFIG_WRITE);
        }
        if (path.startsWith("/api/v1/model-providers")) {
            return Optional.of("GET".equals(method) ? Permission.MODEL_READ : Permission.MODEL_WRITE);
        }
        if (path.startsWith("/api/v1/model-prices")) {
            return Optional.of("GET".equals(method) ? Permission.MODEL_READ : Permission.MODEL_WRITE);
        }
        if (path.matches("/api/v1/agent-specs/[0-9a-fA-F-]{36}/(publish|deactivate)")) {
            return Optional.of(Permission.AGENT_SPEC_PUBLISH);
        }
        if (path.matches("/api/v1/agent-specs/[0-9a-fA-F-]{36}/deployments/[0-9a-fA-F-]{36}")) {
            return Optional.of(Permission.AGENT_SPEC_DEPLOY);
        }
        if (path.startsWith("/api/v1/agent-specs")) {
            return Optional.of("GET".equals(method) ? Permission.AGENT_SPEC_READ : Permission.AGENT_SPEC_WRITE);
        }
        if (path.startsWith("/api/v1/worker-templates")) {
            if (path.matches("/api/v1/worker-templates/[0-9a-fA-F-]{36}/revisions/[0-9]+/review")) {
                return Optional.of(Permission.AGENT_SPEC_REVIEW);
            }
            if (path.matches("/api/v1/worker-templates/[0-9a-fA-F-]{36}/revisions/[0-9]+/publish")) {
                return Optional.of(Permission.AGENT_SPEC_PUBLISH);
            }
            if (path.matches("/api/v1/worker-templates/[0-9a-fA-F-]{36}/revisions/[0-9]+/instances")
                    || path.matches("/api/v1/worker-templates/[0-9a-fA-F-]{36}/instances/[0-9a-fA-F-]{36}/upgrade/[0-9]+")) {
                return Optional.of(Permission.AGENT_SPEC_INSTANTIATE);
            }
            return Optional.of("GET".equals(method) ? Permission.AGENT_SPEC_READ : Permission.AGENT_SPEC_WRITE);
        }
        if (path.startsWith("/api/v1/artifact-quality")) {
            return "GET".equals(method) ? Optional.of(Permission.ARTIFACT_READ) : Optional.empty();
        }
        if (path.startsWith("/api/v1/evaluations")) {
            if ("GET".equals(method)) return Optional.of(Permission.AGENT_SPEC_READ);
            if ("POST".equals(method) && path.equals("/api/v1/evaluations/runs")) {
                return Optional.of(Permission.AGENT_SPEC_WRITE);
            }
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/commands")) {
            return Optional.of(Permission.TASK_CREATE);
        }
        if (path.startsWith("/api/v1/scheduled-tasks")) {
            if ("GET".equals(method)) return Optional.of(Permission.TASK_READ);
            if (path.endsWith("/cancel")) return Optional.of(Permission.TASK_CANCEL);
            if (path.endsWith("/pause") || path.endsWith("/resume")) return Optional.of(Permission.TASK_PAUSE);
            return Optional.of(Permission.TASK_CREATE);
        }
        if (path.startsWith("/api/v1/mcp/")) {
            return Optional.of("GET".equals(method) ? Permission.MCP_READ : Permission.MCP_WRITE);
        }
        if (path.startsWith("/api/v1/matrix/rooms/bindings")) {
            return Optional.of("GET".equals(method) ? Permission.TEAM_READ : Permission.TEAM_WRITE);
        }
        if (path.startsWith("/api/v1/webhooks")) {
            return Optional.of("GET".equals(method) ? Permission.TASK_READ : Permission.TASK_CREATE);
        }
        if (path.startsWith("/api/v1/skills")) {
            if (path.matches("/api/v1/skills/[0-9a-fA-F-]{36}/versions/[0-9a-fA-F-]{36}/review")) {
                return Optional.of(Permission.SKILL_REVIEW);
            }
            if (path.matches("/api/v1/skills/[0-9a-fA-F-]{36}/versions/[0-9a-fA-F-]{36}/(publish|disable)")) {
                return Optional.of(Permission.SKILL_PUBLISH);
            }
            if (path.matches("/api/v1/skills/[0-9a-fA-F-]{36}/versions/[0-9a-fA-F-]{36}/package/(upload|complete)")) {
                return Optional.of(Permission.SKILL_PACKAGE_UPLOAD);
            }
            return Optional.of("GET".equals(method) ? Permission.SKILL_READ : Permission.SKILL_WRITE);
        }
        if (path.startsWith("/api/v1/mcp-servers")) {
            return Optional.of("GET".equals(method) ? Permission.MCP_READ : Permission.MCP_WRITE);
        }
        if (path.startsWith("/api/v1/memory")) {
            return Optional.of("GET".equals(method) ? Permission.MEMORY_READ : Permission.MEMORY_GOVERN);
        }
        if (path.startsWith("/api/v1/sandboxes")) {
            return "GET".equals(method) ? Optional.of(Permission.SANDBOX_READ) : Optional.empty();
        }
        if (path.startsWith("/api/v1/usage/budgets")) {
            if ("GET".equals(method)) return Optional.of(Permission.QUOTA_READ);
            if ("PUT".equals(method) && path.matches("/api/v1/usage/budgets/[0-9a-fA-F-]{36}")) {
                return Optional.of(Permission.QUOTA_WRITE);
            }
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/usage/quota")) {
            if ("GET".equals(method)) return Optional.of(Permission.QUOTA_READ);
            if ("PUT".equals(method) && path.equals("/api/v1/usage/quota")) {
                return Optional.of(Permission.QUOTA_WRITE);
            }
            return Optional.empty();
        }
        if (path.equals("/api/v1/usage/export")) {
            return Optional.of(Permission.USAGE_EXPORT);
        }
        if (path.startsWith("/api/v1/usage")) {
            return "GET".equals(method) ? Optional.of(Permission.USAGE_READ) : Optional.empty();
        }
        if (path.startsWith("/api/v1/dashboard")) {
            if ("GET".equals(method)) return Optional.of(Permission.USAGE_READ);
            if ("PUT".equals(method) && path.matches("/api/v1/dashboard/alert-rules/[^/]+")) {
                return Optional.of(Permission.DASHBOARD_WRITE);
            }
            if ("POST".equals(method) && (path.equals("/api/v1/dashboard/alerts/notify")
                    || path.matches("/api/v1/dashboard/alerts/events/[0-9a-fA-F-]{36}/retry"))) {
                return Optional.of(Permission.DASHBOARD_WRITE);
            }
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/audit-events")) {
            return "GET".equals(method) ? Optional.of(Permission.AUDIT_READ) : Optional.empty();
        }
        return Optional.empty();
    }

}
