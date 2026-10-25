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
  const text = neutralizeDelimiters(String(value));
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

/**
 * Neutralise fence markers inside the payload so a third-party string cannot
 * close the wrapper early.
 */
export function neutralizeDelimiters(text: string): string {
  return text
    .split(UNTRUSTED_BEGIN)
    .join("[PHONEPI_UNTRUSTED_DATA]")
    .split(UNTRUSTED_END)
    .join("[END_PHONEPI_UNTRUSTED_DATA]");
}
