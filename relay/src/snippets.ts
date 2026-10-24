// Ready-to-paste config for each MCP client, returned once with the token so
// the pairing page can show it. Formats follow each client's own docs.

export function snippets(mcp: string, acp: string, token: string): Record<string, unknown> {
  const authorization = `Bearer ${token}`;
  const headers = { Authorization: authorization };
  return {
    claude: `claude mcp add --transport http phone ${mcp} --header "Authorization: ${authorization}"`,
    codex: `export DROID_TOKEN=${token}\ncodex mcp add phone --url ${mcp} --bearer-token-env-var DROID_TOKEN`,
    cursor: { mcpServers: { phone: { url: mcp, headers } } },
    vscode: { servers: { phone: { type: "http", url: mcp, headers } } },
    windsurf: { mcpServers: { phone: { serverUrl: mcp, headers } } },
    zed: { context_servers: { phone: { url: mcp, headers } } },
    mcp_json: { mcpServers: { phone: { type: "http", url: mcp, headers } } },
    acp: { url: acp, headers },
  };
}
