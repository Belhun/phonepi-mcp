# Security roadmap

v1 stays local and simple. Later versions add pairing, tokens, encryption, and Tailscale. This page sets the phases so v1 choices do not block them.

Full threat analysis lives in [`../research/04-security/local-security.md`](../research/04-security/local-security.md) and [`../research/04-security/tailscale.md`](../research/04-security/tailscale.md).

## The starting risk

The current server has no auth. It binds `0.0.0.0:11041`, accepts any client, and gives the newest socket the single phone slot. Any LAN peer could connect and drive SMS, calls, and contacts. v1 sidesteps this by staying off the LAN, not by fixing the server yet.

## Phase 0 — v1 local only (now)

Goal: build and iterate with the smallest exposure.

| Control | Choice |
|---------|--------|
| Transport | `ws://` plaintext, local only |
| Reach | USB `adb reverse tcp:11041 tcp:11041`, app connects to `127.0.0.1` |
| LAN | Avoid; if used, trusted home network only |
| Auth | None (accepted risk for local dev) |
| Telephony tools | Off by default; enable per tool |
| Firewall | Keep port 11041 blocked on Public profile |

Why this is safe enough: with USB forwarding, no other device can reach the socket. The risk window is your own machine.

## Phase 1 — Pairing and tokens (first hardening)

Add app-level auth on top of `ws://` before any wide LAN use.

1. Server enters pairing mode and shows a QR or 8-character code.
2. The app scans or types it, derives a session, and stores a token.
3. The app sends `{ "type": "auth", "device_id", "access_token", "role": "phone" }` as its first frame.
4. The server rejects any socket that does not authenticate within 5 seconds.

Token model from the research:

| Token | Lifetime |
|-------|----------|
| Pairing nonce | 5 minutes, single use |
| Access token | 15–60 minutes |
| Refresh token | Until revoked |

This needs a server change. It is the first item in the hardening milestone.

## Phase 2 — WSS and certificate pinning

Encrypt the channel and pin the server identity.

1. Generate a local CA and a server cert with SANs for `127.0.0.1`, the LAN IP, and the Tailscale hostname.
2. Ship the CA public cert to the app during pairing (in the QR payload or a follow-up frame).
3. The app's `network_security_config.xml` trusts only that CA and disables cleartext in release.
4. Pin the server SPKI.

Pinning is worth it here because this is a high-privilege control channel.

## Phase 3 — Tailscale-only remote (your planned target)

Tailscale gives each device a stable `100.x` address over an encrypted WireGuard mesh, with no public port.

| Control | Choice |
|---------|--------|
| Bind | Server binds the Tailscale interface IP, not `0.0.0.0` |
| Reach | Only devices in your tailnet |
| ACLs | Tailscale policy limits which nodes reach port 11041 |
| Firewall | Allow inbound 11041 only from `100.64.0.0/10` |
| App host | The PC's `100.x` address or MagicDNS name |

Combined with Phase 1 tokens and Phase 2 WSS, this gives encrypted, authenticated remote access with no exposed public surface. Tailscale detail in [`../research/04-security/tailscale.md`](../research/04-security/tailscale.md).

## Phase 4 — Defense in depth

| Control | Effect |
|---------|--------|
| Per-tool enable switches (already in v1) | Disable `send_sms` / `make_call` unless turned on |
| Phone confirmation for destructive actions | Block silent abuse from prompt injection |
| Number allowlist / prefix block | Stop premium or international cost attacks |
| Rate limit SMS and calls | Stop rapid-fire spam |
| Local audit log | Record connect, auth, tool name; no message bodies |
| Constant-time token compare | Stop timing attacks on auth |

## How v1 stays forward-compatible

- The connection settings already hold host and port, so swapping `127.0.0.1` for a `100.x` address needs no redesign.
- The first-frame model leaves room for an `auth` frame later.
- The network security config is the only release blocker for WSS; it is one file.
- Tool gating exists from day one, so confirmations and allowlists slot into the existing handler check.

## Telephony safety note

SMS and call tools take instructions from an AI, which can be steered by prompt injection. Keep these tools off by default, add a phone-side confirmation before the first send per session, and block non-local prefixes. These are app-side controls; they work even before the server gains auth. See [`../research/02-tools/comms-tools.md`](../research/02-tools/comms-tools.md).
