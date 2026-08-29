import { UserManager, type UserManagerSettings } from 'oidc-client-ts';

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
  const issuer = import.meta.env.VITE_OIDC_ISSUER || 'http://localhost:8180/realms/agentteams';
  return {
    authority: issuer,
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID || 'agentteams-console',
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/login`,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
    userStore: new MemoryUserStore(),
  };
}

export function createOidcUserManager() {
  return new UserManager(oidcSettings());
}
