/**
 * Shared placeholder / example markers for WS and read tokens.
 * Never log the token value; only match against these substrings.
 */
export const WEAK_TOKEN_MARKERS = [
  "placeholder",
  "choose-a-long",
  "changeme",
  "example",
  "secret-at-least",
];

export function tokenContainsWeakMarker(token: string): boolean {
  const lower = token.trim().toLowerCase();
  return WEAK_TOKEN_MARKERS.some((marker) => lower.includes(marker));
}
