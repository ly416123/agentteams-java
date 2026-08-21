package io.agentteams.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional mutual-TLS material for the standalone Agent gRPC listener. */
@ConfigurationProperties(prefix = "agentteams.gateway.grpc.tls")
public class GrpcTlsProperties {
    private boolean enabled;
    private String certificateChain = "";
    private String privateKey = "";
    private String trustCertificateCollection = "";

    public void validate() {
        if (!enabled) return;
        require(certificateChain, "agentteams.gateway.grpc.tls.certificate-chain");
        require(privateKey, "agentteams.gateway.grpc.tls.private-key");
        require(trustCertificateCollection, "agentteams.gateway.grpc.tls.trust-certificate-collection");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when gRPC TLS is enabled");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCertificateChain() { return certificateChain; }
    public void setCertificateChain(String certificateChain) { this.certificateChain = certificateChain; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getTrustCertificateCollection() { return trustCertificateCollection; }
    public void setTrustCertificateCollection(String trustCertificateCollection) {
        this.trustCertificateCollection = trustCertificateCollection;
    }
}
