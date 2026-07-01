# PhonePi Google Messages bridge

Wraps [openmessage](https://github.com/FreesoSaiFared/openmessage) (vendored under `vendor/openmessage`) for SMS + **RCS** history via Google Messages web pairing.

## Prerequisites

- **Go 1.22+** ([install](https://go.dev/dl/)) — required only to build; `go.mod` may pin a newer toolchain
- **Google Messages** on your Android phone

## Where to run commands

The executable lives at `messages-bridge\phonepi-gmessages.exe`. It is **not** in the repo root.

| From repo root | From `messages-bridge\` |
|----------------|-------------------------|
| `.\pair-gmessages.ps1` | `.\phonepi-gmessages.exe pair` |
| `.\pair-gmessages.bat` | |
| `.\serve-gmessages.ps1` | `.\phonepi-gmessages.exe serve` |

Root scripts `cd` into `messages-bridge` and build the exe first if it is missing.

## Build (Windows)

From repo root:

```powershell
.\messages-bridge\build.ps1
```

Or from this directory:

```powershell
cd messages-bridge
go build -o phonepi-gmessages.exe .
```

## Pair (one time)

From repo root (recommended):

```powershell
.\pair-gmessages.ps1
```

Or from `messages-bridge\`:

```powershell
.\phonepi-gmessages.exe pair
```

Scan the QR code: **Google Messages → Settings → Device pairing → Pair a device**.

Session saves to `%USERPROFILE%\.local\share\phonepi-gmessages\session.json`.

## Run sync server

From repo root (recommended):

```powershell
.\serve-gmessages.ps1
```

Or from `messages-bridge\`:

```powershell
.\phonepi-gmessages.exe serve
```

Starts:

| Service | URL |
|---------|-----|
| Web UI | http://localhost:11042 |
| HTTP tool bridge | POST http://localhost:11042/api/tool |
| Tool list | GET http://localhost:11042/api/tools |

The PhonePi Node MCP server calls the tool bridge for `gm_*` tools. **No Android WebSocket required** for Google Messages reads.

### Auto-start with MCP

When the PhonePi MCP server starts (`server/src/index.ts` → `ensureGmessagesSidecar`), it tries to launch the bridge automatically if:

1. `%USERPROFILE%\.local\share\phonepi-gmessages\session.json` exists (you have paired), and
2. Port `11042` (or `OPENMESSAGES_PORT`) is not already listening.

If the port is in use, MCP logs that the bridge is already running and does not spawn a second copy.

The sidecar is started **detached** and is **left running** when MCP shuts down (Cursor restarts, etc.) so message sync keeps going. Stop it manually with Ctrl+C in the terminal where you ran `serve`, or end the `phonepi-gmessages` process in Task Manager.

You can still run `.\serve-gmessages.ps1` manually before pairing or for debugging.

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `PHONEPI_GMESSAGES_DATA_DIR` | `%USERPROFILE%\.local\share\phonepi-gmessages` | SQLite DB + session |
| `OPENMESSAGES_PORT` | `11042` | HTTP port |
| `PHONEPI_GMESSAGES_URL` | `http://127.0.0.1:11042` | Set on Node MCP server |

## Odysseus MCP setup

In Odysseus Admin → MCP servers, use **direct stdio** on `index.js`. Do **not** use `cli.js start`.

`cli.js start` runs `ensureCleanStart`, which kills any process on port 11041. If Cursor, another Odysseus reconnect, or a manual `phonepi-mcp start` runs while Odysseus is connected, the PhonePi subprocess dies. Odysseus then logs empty errors like `MCP tool call failed: mcp__...__gm_get_messages:` (`ClosedResourceError` has no message text).

Recommended config:

| Field | Value |
|-------|-------|
| Command | `node` |
| Args | `["F:\\Codeing Project\\phonepi-mcp\\server\\dist\\index.js", "--stdio"]` |
| Env (optional) | `PHONEPI_GMESSAGES_URL=http://127.0.0.1:11042` |

After changing args, **disconnect and reconnect** the PhonePi MCP server in Odysseus (or restart Odysseus).

Avoid running PhonePi MCP in Cursor and Odysseus at the same time unless you accept one killing the other.

## Test Mom / RCS thread

1. `phonepi-gmessages serve` (wait for backfill)
2. Open web UI → find Mom conversation
3. Or via MCP: `gm_list_conversations` → `gm_get_conversation` with conversation ID
4. Or: `gm_search_messages` with query text from the chat
