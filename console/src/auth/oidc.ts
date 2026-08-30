import { UserManager, type UserManagerSettings } from 'oidc-client-ts';

export const RETURN_TO_STORAGE_KEY = 'agentteams.oidc.returnTo';

type RuntimeConfig = {
  oidcIssuer?: string;
  oidcClientId?: string;
};

/**
 * The local LAN demo is intentionally served over HTTP. Web Crypto is not
 * available for PKCE on a non-secure LAN origin, while HTTPS deployments and
 * localhost remain PKCE-protected.
 */
export function shouldDisablePkce(
  location: Pick<Location, 'protocol' | 'hostname'> = window.location,
) {
  return (
    location.protocol === 'http:' &&
    !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
  );
}

function runtimeConfig(): RuntimeConfig {
  return window.__AGENTTEAMS_CONFIG__ || {};
}

export function saveReturnTo(returnTo: string) {
  if (returnTo.startsWith('/') && !returnTo.startsWith('//')) {
    sessionStorage.setItem(RETURN_TO_STORAGE_KEY, returnTo);
  }
}

export function consumeReturnTo(state?: unknown, fallback = '/') {
  const stateReturnTo =
    state && typeof state === 'object' && 'returnTo' in state
      ? (state as { returnTo?: unknown }).returnTo
      : undefined;
  const stored = sessionStorage.getItem(RETURN_TO_STORAGE_KEY);
  sessionStorage.removeItem(RETURN_TO_STORAGE_KEY);
  const candidate = typeof stateReturnTo === 'string' ? stateReturnTo : stored;
  return candidate && candidate.startsWith('/') && !candidate.startsWith('//')
    ? candidate
    : fallback;
}

class MemoryUserStore {
  private values = new Map<string, string>();

  async get(key: string): Promise<string | null> {
    return this.values.get(key) || null;
  }
  async set(key: string, value: string): Promise<void> {
    this.values.set(key, value);
  }
  async remove(key: string): Promise<string | null> {
    const value = this.values.get(key) || null;
    this.values.delete(key);
    return value;
  }
  async getAllKeys(): Promise<string[]> {
    return [...this.values.keys()];
  }
}

export function oidcSettings(): UserManagerSettings {
  const config = runtimeConfig();
  const issuer =
    import.meta.env.VITE_OIDC_ISSUER ||
    config.oidcIssuer ||
    'http://localhost:8180/realms/agentteams';
  return {
    authority: issuer,
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID || config.oidcClientId || 'agentteams-console',
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/login`,
    response_type: 'code',
    scope: 'openid profile email',
    disablePKCE: shouldDisablePkce(),
    automaticSilentRenew: true,
    userStore: new MemoryUserStore(),
  };
}

export function createOidcUserManager() {
  return new UserManager(oidcSettings());
}
