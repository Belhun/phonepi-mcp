# PhonePi docs

Index of feature docs. The combined setup list for Alex is [ALEX-CHECKLIST.md](ALEX-CHECKLIST.md). Never print `PHONEPI_WS_TOKEN`, `PHONEPI_READ_TOKEN`, wake-hook secrets, or webhook URLs.

| Doc | What it covers |
| --- | --- |
| [ALEX-CHECKLIST.md](ALEX-CHECKLIST.md) | Sideload (release-signed APKs), grants, PhonePi quiet, Ask assistant, Wear, read-token / wake-hook env names |
| [BOT-SKILL.md](BOT-SKILL.md) | Bot-facing tools and commands (sensitive reads need `PHONEPI_READ_TOKEN`) |
| [presence.md](presence.md) | `get_presence`, work hours config, pause/clear |
| [health.md](health.md) | Health Connect / Samsung Health, `get_health_summary` |
| [assistant-notifications.md](assistant-notifications.md) | Watch-actionable notifications, Ask assistant, inbound prompts, wake hook |
| [send-text.md](send-text.md) | `request_send_text` / `get_send_status` |
| [release-signing.md](release-signing.md) | Companion and Wear release key (same `applicationId`) |
| [location-timeline-feasibility.md](location-timeline-feasibility.md) | Why PhonePi cannot pull Google Timeline |
| [LEARNINGS.md](LEARNINGS.md) | Standing notes for this repo |

Companion **0.3.5** (versionCode **17**). Wear app **0.1.0** (versionCode **1**).
