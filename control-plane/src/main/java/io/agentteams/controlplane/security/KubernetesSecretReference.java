package io.agentteams.controlplane.security;

import java.net.URI;

/** Strict, non-secret reference format for a Kubernetes Secret data key. */
public record KubernetesSecretReference(String namespace, String name, String key) {

    public static KubernetesSecretReference parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!("k8s".equals(uri.getScheme()) || "kubernetes".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                    || uri.getHost() == null || uri.getPath() == null || !uri.getPath().startsWith("/")
                    || uri.getPath().length() < 2 || uri.getPath().substring(1).contains("/")
                    || uri.getRawFragment() == null || uri.getRawFragment().isBlank()
                    || value.indexOf('%') >= 0) {
                return null;
            }
            String namespace = uri.getHost();
            String name = uri.getPath().substring(1);
            String key = uri.getRawFragment();
            if (!validDnsName(namespace) || !validDnsName(name) || !validKey(key)) {
                return null;
            }
            return new KubernetesSecretReference(namespace, name, key);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean validDnsName(String value) {
        return value.length() <= 253 && value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*");
    }

    private static boolean validKey(String value) {
        return value.length() <= 253 && value.matches("[A-Za-z0-9._-]+");
    }
}
