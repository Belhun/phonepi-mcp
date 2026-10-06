/** Tools a local PowerShell assistant may call over the loopback HTTP API. */
export const READ_ONLY_HTTP_TOOLS = new Set<string>([
  "get_battery_level",
  "get_recent_calls",
  "get_contacts",
  "search_contacts",
  "get_contact_by_id",
  "get_all_snippets",
  "get_snippet_by_id",
  "get_snippets_by_type",
  "search_snippets",
]);

export function isReadOnlyHttpTool(name: string): boolean {
  return READ_ONLY_HTTP_TOOLS.has(name);
}
