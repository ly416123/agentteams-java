package io.agentteams.controlplane.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned OIDC verification settings; no client secret is stored here. */
@ConfigurationProperties(prefix = "agentteams.security.oidc")
public class OidcSecurityProperties {
    private boolean enabled;
    private String issuerUri = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String tenantClaim = "tenant";
    private String projectClaim = "project";
    private String teamClaim = "team";
    private String permissionsClaim = "permissions";

    public void validate() {
        if (!enabled) {
            throw new IllegalStateException("agentteams.security.oidc.enabled must be true when API authentication is enabled");
        }
        require(issuerUri, "agentteams.security.oidc.issuer-uri");
        require(jwkSetUri, "agentteams.security.oidc.jwk-set-uri");
        require(audience, "agentteams.security.oidc.audience");
        require(tenantClaim, "agentteams.security.oidc.tenant-claim");
        require(projectClaim, "agentteams.security.oidc.project-claim");
        require(teamClaim, "agentteams.security.oidc.team-claim");
        require(permissionsClaim, "agentteams.security.oidc.permissions-claim");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String issuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String jwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String audience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String tenantClaim() { return tenantClaim; }
    public void setTenantClaim(String tenantClaim) { this.tenantClaim = tenantClaim; }
    public String projectClaim() { return projectClaim; }
    public void setProjectClaim(String projectClaim) { this.projectClaim = projectClaim; }
    public String teamClaim() { return teamClaim; }
    public void setTeamClaim(String teamClaim) { this.teamClaim = teamClaim; }
    public String permissionsClaim() { return permissionsClaim; }
    public void setPermissionsClaim(String permissionsClaim) { this.permissionsClaim = permissionsClaim; }
}
