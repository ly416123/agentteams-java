package io.agentteams.manager.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentteams.manager.security")
public class ManagerSecurityProperties {
    private boolean enabled = true;
    private String issuerUri = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String tenantClaim = "tenant";
    private String projectClaim = "project";
    private String teamClaim = "team";
    private String permissionsClaim = "permissions";

    public void validate() {
        if (!enabled) throw new IllegalStateException("Manager API authentication cannot be disabled");
        require(issuerUri, "issuer-uri");
        require(jwkSetUri, "jwk-set-uri");
        require(audience, "audience");
        require(tenantClaim, "tenant-claim");
        require(projectClaim, "project-claim");
        require(teamClaim, "team-claim");
        require(permissionsClaim, "permissions-claim");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(
                "agentteams.manager.security." + name + " is required");
    }

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String issuerUri() { return issuerUri; }
    public void setIssuerUri(String value) { issuerUri = value; }
    public String jwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String value) { jwkSetUri = value; }
    public String audience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public String tenantClaim() { return tenantClaim; }
    public void setTenantClaim(String value) { tenantClaim = value; }
    public String projectClaim() { return projectClaim; }
    public void setProjectClaim(String value) { projectClaim = value; }
    public String teamClaim() { return teamClaim; }
    public void setTeamClaim(String value) { teamClaim = value; }
    public String permissionsClaim() { return permissionsClaim; }
    public void setPermissionsClaim(String value) { permissionsClaim = value; }
}
