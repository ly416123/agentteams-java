package io.agentteams.controlplane.security;

/**
 * Explicit External Secrets boundary. It validates references but performs no
 * network call until a deployment-owned adapter is supplied.
 */
public final class ExternalSecretsSecretResolver implements SecretResolver {

    private final ExternalSecretStatusReader statusReader;
    private final KubernetesSecretMetadataReader metadataReader;

    public ExternalSecretsSecretResolver() {
        this(null, null);
    }

    public ExternalSecretsSecretResolver(ExternalSecretStatusReader statusReader,
            KubernetesSecretMetadataReader metadataReader) {
        this.statusReader = statusReader;
        this.metadataReader = metadataReader;
    }

    @Override
    public Resolution resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return new Resolution(Status.MISSING);
        }
        try {
            String reference = CredentialReferenceValidator.normalize(credentialRef);
            ExternalSecretReference parsed = ExternalSecretReference.parse(reference);
            if (parsed == null) {
                // Preserve the validation-only compatibility contract for
                // legacy provider-neutral references until an adapter exists.
                return new Resolution(statusReader == null && metadataReader == null
                        ? Status.UNAVAILABLE : Status.INVALID_REFERENCE);
            }
            if (statusReader == null || metadataReader == null) {
                return new Resolution(Status.UNAVAILABLE);
            }
            ExternalSecretStatus external = statusReader.read(parsed.namespace(), parsed.name());
            if (external == null || external.state() == ExternalSecretStatus.State.UNKNOWN
                    || external.state() == ExternalSecretStatus.State.NOT_READY
                    || !external.generationIsCurrent()) {
                return new Resolution(Status.UNAVAILABLE);
            }
            if (external.state() == ExternalSecretStatus.State.NOT_FOUND
                    || external.targetSecretName().isBlank()) {
                return new Resolution(external.state() == ExternalSecretStatus.State.NOT_FOUND
                        ? Status.MISSING : Status.UNAVAILABLE);
            }
            KubernetesSecretMetadataReader.Metadata metadata = metadataReader.read(parsed.namespace(),
                    external.targetSecretName());
            return new Resolution(metadata != null && metadata.keys().contains(parsed.key())
                    ? Status.RESOLVED : Status.MISSING);
        } catch (IllegalArgumentException error) {
            return new Resolution(Status.INVALID_REFERENCE);
        } catch (RuntimeException error) {
            return new Resolution(Status.UNAVAILABLE);
        }
    }

    private record ExternalSecretReference(String namespace, String name, String key) {
        private static ExternalSecretReference parse(String value) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                if (!"externalsecret".equals(uri.getScheme()) || uri.getHost() == null
                        || uri.getPath() == null || !uri.getPath().startsWith("/")
                        || uri.getPath().length() < 2 || uri.getPath().substring(1).contains("/")
                        || uri.getRawFragment() == null || uri.getRawFragment().isBlank()
                        || uri.getQuery() != null || uri.getUserInfo() != null || uri.getPort() != -1
                        || value.indexOf('%') >= 0) {
                    return null;
                }
                String namespace = uri.getHost();
                String name = uri.getPath().substring(1);
                String key = uri.getRawFragment();
                if (!namespace.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
                        || !name.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
                        || !key.matches("[A-Za-z0-9._-]+")) {
                    return null;
                }
                return new ExternalSecretReference(namespace, name, key);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
    }
}
