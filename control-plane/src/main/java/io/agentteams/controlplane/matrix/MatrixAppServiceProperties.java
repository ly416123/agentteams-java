package io.agentteams.controlplane.matrix;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for authenticating Matrix homeserver AppService requests. */
@ConfigurationProperties(prefix = "agentteams.matrix.appservice")
public class MatrixAppServiceProperties {
    private boolean authEnabled;
    private String hsToken = "";

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public String getHsToken() {
        return hsToken;
    }

    public void setHsToken(String hsToken) {
        this.hsToken = hsToken == null ? "" : hsToken;
    }
}
