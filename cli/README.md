# @withaster/droid

Pair this computer with Droid on your Android phone, then reach the phone from
any MCP client or ACP editor. The command is `aster-droid`.

```sh
npx -y @withaster/droid pair --adb               # phone on a USB cable
npx -y @withaster/droid pair --lan 192.168.1.20  # phone on this Wi-Fi or Tailscale
npx -y @withaster/droid setup all                # add Droid to Claude Code, Codex, Cursor, Zed, JetBrains and VS Code
```

Needs Node 20 or later.

## Pairing

| Command | Path | Cost |
| --- | --- | --- |
| `aster-droid pair --adb` | Straight to the phone on a USB cable, through `adb forward` | Free |
| `aster-droid pair --lan 192.168.1.20` | Straight to the phone on your network, or its Tailscale address | Free |
| `aster-droid pair` | `--adb` when adb sees exactly one phone; otherwise it says how to pair over Wi-Fi or USB | Free |
| `aster-droid pair --relay <url>` | This computer shows a QR code, Droid scans it (Reach from anywhere, Scan a computer) | Aster Relay, coming soon |
| `aster-droid pair --code 7K2Q-9MXD` | Type the code Droid shows, or paste its pair link, then tap Allow on the phone | Aster Relay, coming soon |

Aster Relay, for reaching the phone from anywhere, is coming soon, so a bare
`pair` never dials it. The relay paths work today with a relay you host
yourself: pass `--relay <url>` or set `ASTER_RELAY_URL`. The LAN and adb paths talk to
`asterctl` on the phone directly, with the two keys Droid shows under Connect a
computer: the other agents' key (MCP) and the editors' key (ACP). Paste them
when asked, or pass `--token`, `--mcp-token` and `--acp-token`. For the LAN, set
Droid's other agents' and editors' addresses to `0.0.0.0` so they listen on
Wi-Fi; over adb they can stay on the phone's loopback.

Pairings are kept in `~/.config/aster-droid/credentials.json`, mode 0600 in a
0700 directory. Several phones can be paired; the last one paired is the
default, and `--phone <id or name>` picks another. `ASTER_DROID_HOME` moves the
file, and `ASTER_RELAY_URL` or `--relay` points at a self-hosted relay.

## Editors (ACP)

Zed and JetBrains start agents as local processes, so they launch:

```sh
npx -y @withaster/droid acp
```

which pipes the editor's JSON-RPC to the agent running on the phone. Before
pairing it offers a terminal sign-in, so the editor's login button runs
`aster-droid pair` for you. Only one editor can drive the phone at a time.

## MCP clients

```sh
npx -y @withaster/droid mcp
```

is a stdio MCP server that forwards to the phone. `setup` uses it by default,
so the token stays in this computer's credentials file and out of each
client's config. `setup <client> --http` writes the phone's URL and token into
the client's config instead, for clients that prefer HTTP.

## setup

```sh
aster-droid setup zed            # or claude, codex, cursor, jetbrains, vscode, all
aster-droid setup all --print    # show every snippet, write nothing
```

| Client | File | Entry |
| --- | --- | --- |
| Claude Code | `~/.claude.json` | `mcpServers.droid` |
| Codex | `~/.codex/config.toml` | `[mcp_servers.droid]` |
| Cursor | `~/.cursor/mcp.json` | `mcpServers.droid` |
| Zed | `~/.config/zed/settings.json` | `agent_servers.Droid` and `context_servers.droid` |
| JetBrains | `~/.jetbrains/acp.json` | `agent_servers.Droid` |
| VS Code | `Code/User/mcp.json` and `settings.json` | `servers.droid`, and `acp.agents.Droid` for the ACP Client extension |

An entry that already exists is left alone, and a file with comments is never
rewritten, because the comments would be lost; the snippet is printed instead
for you to paste.

## Other commands

- `aster-droid status` lists paired phones and whether each answers. `--json` for scripts.
- `aster-droid unpair [phone]` forgets a phone here (`--all` for every one). A relay token
  keeps working until the phone revokes it, so also remove the computer in Droid.

## Develop

```sh
npm install
npm test
npm run typecheck
```

`registry/acp/` holds the entry for the ACP registry, and `server.json` the one
for the MCP registry. Bump their versions with `package.json`.
