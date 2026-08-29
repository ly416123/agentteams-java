/// <reference types="vite/client" />

interface Window {
  __AGENTTEAMS_CONFIG__?: {
    apiBasePath?: string;
    oidcIssuer?: string;
    oidcClientId?: string;
  };
}
