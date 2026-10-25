/**
 * Wrap untrusted text so bots treat it as data, not as instructions.
 * Prompt/reply text, app names, and workout titles go through this.
 */

export const UNTRUSTED_BEGIN = "<<<PHONEPI_UNTRUSTED_DATA>>>";
export const UNTRUSTED_END = "<<<END_PHONEPI_UNTRUSTED_DATA>>>";

export interface UntrustedText {
  untrusted: true;
  provenance: string;
  text: string;
}

export function wrapUntrusted(
  value: string | null | undefined,
  provenance: string
): UntrustedText | null {
  if (value == null) return null;
  const text = String(value);
  if (text === "") return null;
  return {
    untrusted: true as const,
    provenance,
    text: `${UNTRUSTED_BEGIN}\n${text}\n${UNTRUSTED_END}`,
  };
}

export function wrapUntrustedOrEmpty(
  value: string | null | undefined,
  provenance: string
): UntrustedText {
  return (
    wrapUntrusted(value, provenance) ?? {
      untrusted: true as const,
      provenance,
      text: `${UNTRUSTED_BEGIN}\n${UNTRUSTED_END}`,
    }
  );
}
