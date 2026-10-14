export function readAccessTokenClaim(token: string | undefined, claim: string): string | undefined {
  if (!token || !claim) return undefined;
  try {
    const encodedPayload = token.split('.')[1];
    if (!encodedPayload) return undefined;
    const base64 = encodedPayload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
    const payload: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return undefined;
    const value = (payload as Record<string, unknown>)[claim];
    return typeof value === 'string' && value.trim() ? value : undefined;
  } catch {
    return undefined;
  }
}
