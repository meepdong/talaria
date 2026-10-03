# Roadmap (draft v0.2)

The rule: **architecture for every platform, ship one platform at a time.** Each milestone is usable on its own. Android is the reference client; desktop and iOS follow.

## M−1 — Validate (one evening)
- Post this design on Hermes issues [#118010](https://github.com/NousResearch/hermes-agent/issues/118010) and [#126292](https://github.com/NousResearch/hermes-agent/issues/126292), plus r/hermesagent and r/selfhosted.
- Ask: Would you use it? Which first feature matters most? Which platform do you use?
- **Exit:** a handful of people say they would try it, or clear feedback changes the plan.

## M0 — Bridge core and secure pairing
- `spec/`: JSON Schemas for PROTOCOL messages, plus test vectors (signatures, handshake, SAS derivation).
- `bridge/`: TNP server (handshake, heartbeats), device registry, `talaria pair` (QR in the terminal, `talaria://` link, short code), **SAS confirmation prompt**, `talaria devices list|revoke`.
- `tools/tnp-cli/`: terminal client that pairs by pasted link.
- **Exit:** pair the CLI client by link; tests pass for bad signatures, replays, expired or reused tokens, revoked devices, and rejected SAS.

## M1 — Android: pairing and connection status *(first app checkpoint)*
- KMP skeleton (`core/protocol`, `core/security`, `ui`, `androidApp`).
- Pair by QR scan or pasted link; SAS screen; key in Android Keystore.
- Foreground-service session, reconnect with backoff, battery onboarding (OnePlus first).
- **Connection status screen:** network, bridge and agent layers, latency, last connected, reconnect countdown, "Test" button (`status.get` / `status`).
- **Exit:** a week of stable connection on a OnePlus 10 Pro; every failure shows the right layer and fix.

## M2 — Chat
- One agent via the bridge chat proxy (Hermes Responses API, named conversations).
- Streaming replies, tool-progress indicators, cancel, history.
- **Voice:** on-device speech-to-text (Android `SpeechRecognizer`), edit before send, optional auto-send; spoken replies via TTS.
- **Attachments:** photos (downscaled, GPS stripped), PDFs (page picking), files; blob upload.
- **Exit:** used daily for a week instead of Telegram, including voice and photo messages.

## M2b — Voice replies (basic)
- "Reply to <contact> on <app>: <message>": on-device speech-to-text, intent parsing, **local** contact matching with disambiguation.
- Exact mode (free) and Polish mode (`compose.polish`).
- Confirmation card, read aloud, with voice commands "send / change… / cancel".
- Delivery: SMS direct; WhatsApp and Telegram **click-to-chat pre-fill** (you tap Send); Talaria agents natively.
- Entry points: in-app mic, Quick Settings tile, Talaria as default assistant.
- **Exit:** a week of replying to friends by voice with zero wrong-recipient sends.

## M2c — Default assistant (basic)
- Qualify for Android's **default digital assistant** role via `ACTION_ASSIST`; onboarding that walks through choosing Talaria, setting OxygenOS "press and hold power button" to the assistant, and setting Tailscale as **always-on VPN**.
- Assistant overlay: listen → transcript → answer, with spoken replies starting at the first sentence.
- **On-device command router:** timers, alarms, calls, open app, navigate, media play/pause, voice replies (M2b). Everything else goes to the configurable **default assistant agent**.
- Locked-device rules (safe actions only).
- **Exit:** a week of using the power button instead of Gemini; phone commands under 1 s and working offline.

## M3 — Multiple agents
- Bridge agent registry (`agents.yaml`): Hermes profiles and OpenAI-compatible endpoints, with roles, modalities and cost tiers.
- Chat list with agent picker; one conversation per agent.
- **Agent profile screen:** soul (SOUL.md), model, tools/skills, spend; "Message privately".
- Opt-in **soul editing** with diff, revision check and version history.
- Modality warnings ("Scout can't see images").
- **Exit:** three agents (e.g. Meep / Coder / Scout) in daily use; souls readable in the app.

## M4 — Agent group chats
- WhatsApp-style group chat: per-agent colours and labels, @mentions, reply-to, status ticks.
- **Group info** screen: purpose, members with roles and models, routing mode, reply cap, budget, media.
- Bridge group router: conductor / mention-only / round-robin; speaker-labelled context; reply caps; daily budgets.
- **Exit:** a trip-planning group with a conductor and a cheap researcher, staying within its budget for a week.

## M5 — Multi-agent workflows
- Documented setup for cheap workers under a strong conductor (`delegation.model`, `max_concurrent_children`).
- **Workflow view** from Hermes run events: worker cards with goal, model, status and cost; cancel.
- Per-conversation and per-workflow budgets.
- Later: per-task models via worker profiles and Kanban.
- **Exit:** a research task fans out to cheap workers, with total cost visible and at least 50% lower than the same task run on the conductor model alone.

## M6 — Notifications and background
- `notify.show` with user channels and reply actions; MCP `device_notify`; wake push (ntfy / UnifiedPush).
- Full **`VoiceInteractionService`** assistant: true overlay session, lock-screen session, opt-in screen context with per-use confirmation and an app deny-list.
- **Exit:** agent alerts arrive reliably for a week, including after the phone has been idle overnight.

## M7 — Events and rules v1
- Outbox with acks; bridge event store; webhook forwarding to Hermes.
- Triggers: time, geofence, Wi-Fi, power. Actions: notify, speak, emit event, ask agent.
- **Schedules:** a unified Schedules screen; the scheduling router (device rule vs Hermes cron); `schedules.*` via the bridge; per-run cost display and a cheap model pinned by default; server jobs reading device data with stale fallback.
- **Exit:** "arrive at office → agenda" and an 8am briefing run reliably for a week.

## M8 — Phone data, safely
- Notification and SMS forwarding with allow-lists, OTP redaction, `redact_source`; UPI expense template; "what left this device" log.
- Tier 2 approval engine and approval cards.
- **Exit:** a week of automatic UPI expense logging with zero raw SMS stored off-device.

- Assistant power actions: `media.control` (media sessions), `dnd.set`, optional Shizuku for system toggles.
- **Experimental:** opt-in custom wake word (foreground service + on-device engine), with schedule limits.

## M8b — Voice replies (hands-free)
- Deliver through the target chat's **notification Reply action** when one exists, which is fully hands-free.
- **Incoming messages read aloud** (opt-in per app) with spoken replies: "Asha says… Reply?"
- "Reply to that" for the most recent message; headset-button trigger.
- **Exit:** a full drive (or walk with earphones) of reading and replying by voice without touching the phone.

## M8c — Smart replies (optional, later)
- **Draft mode:** the agent suggests replies to incoming messages and you send them with one tap.
- **Auto mode** only for allow-listed contacts and situations (driving, sleeping, in a meeting), labelled as sent by your assistant, rate-limited, never in groups by default.
- Telegram Business bot integration as the official "reply as you" route.

## M9 — Desktop (Windows and Linux)
- Tray app, chat, notifications, `script.run` with manifests, shared folder, global hotkey; MSI/EXE and DEB/RPM builds.
- **Exit:** "run my backup script on the laptop" from the phone, approved on the laptop.

## M10 — Plain-language rules and scripting
- `rules.propose` with diff UI; a Hermes skill teaching the rule schema; sandboxed JS.
- **Exit:** five everyday automations created only by asking the agent.

## M11 — Wearables and car
- Wear OS relay (notifications, haptics, quick replies, voice replies); one glasses SDK proof of concept.
- Talaria's own agent chats in **Android Auto** through standard messaging notifications (read aloud, reply by voice).

## M12 — iOS
- Chat, notifications, location, camera, Shortcuts; APNs wake via relay (decision pending).

## Not in v1
- Multi-user bridges, device-to-device messaging, live audio/video streaming, a Play Store build with SMS, AI screen-tapping automation.

## Working agreements
- Every protocol change updates `spec/` first, with schema and test vectors, then code.
- Security-relevant changes (tiers, approvals, redaction, pairing, budgets) need a written rationale in the pull request.
- Releases: semantic versioning; signed APKs; changelog.
