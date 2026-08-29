let accessToken: string | undefined;
export function setMemoryAccessToken(token?: string) {
  accessToken = token;
}
export function getMemoryAccessToken() {
  return accessToken;
}
