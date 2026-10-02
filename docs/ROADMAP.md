# Roadmap (draft v0.1)

The rule: **architecture for every platform, ship one platform at a time.** Each milestone is usable on its own.

## M−1 — Validate (one evening)
- Post this design on Hermes issues [#118010](https://github.com/NousResearch/hermes-agent/issues/118010) and [#126292](https://github.com/NousResearch/hermes-agent/issues/126292), plus r/hermesagent and r/selfhosted.
- Ask three questions: Would you use it? Which first feature matters most? Which platform do you use?
- **Exit:** at least a handful of people say they would try it, or clear feedback changes the plan.

## M0 — Protocol and bridge skeleton
- `spec/`: JSON Schemas for every message in PROTOCOL v0.1, plus test vectors (signatures, handshakes).
- `bridge/`: pairing CLI, TNP server (handshake, heartbeats, capability registry), MCP server exposing `devices_list` and `device_notify`.
- `tools/tnp-cli/`: a terminal client that pairs and responds to `notify.show`.
- **Exit:** from Hermes, *"send a test notification to my terminal client"* works end to end, and auth tests pass (bad signature, replay, revoked device).

## M1 — Android: pair, chat, notify
- KMP project skeleton (`core/protocol`, `core/security`, `ui`, `androidApp`).
- QR pairing, foreground-service session, reconnect with backoff, a battery onboarding screen (OnePlus first).
- Chat through the bridge proxy with streaming replies.
- `notify.show` with user channels and reply actions; `device.status`.
- **Exit:** used daily for a week as a Telegram replacement on a OnePlus 10 Pro, with no missed notifications.

## M2 — Events and rules v1
- Outbox with acks and resume; bridge event store; webhook forwarding to Hermes.
- Rule engine: `time.*`, `geofence.*`, `wifi.*`, `power.*` triggers; `notify.show`, `tts.speak`, `event.emit` and `agent.ask` actions.
- Rules screen (list, enable/disable, history).
- **Exit:** "arrive at office → agenda notification" and a daily 8am briefing both run reliably for a week.

## M3 — Phone data, safely
- `notification.posted` and `notifications.recent` with app allow-lists and OTP redaction.
- `sms.received` (sideload build) with sender allow-lists and `redact_source`.
- UPI expense rule template; "what left this device" log.
- Approval engine for tier 2; approval cards; audit sync.
- **Exit:** a week of automatic UPI expense logging with zero raw SMS stored off-device (verified via the bridge DB).

## M4 — Desktop (Windows and Linux)
- `desktopApp` (JVM): tray app, autostart, chat, `notify.show`.
- `script.run` with manifests; shared folder `files.*`; global hotkey to talk to the agent (Windows, X11).
- MSI/EXE and DEB/RPM builds via GitHub Actions.
- **Exit:** from the phone, "run my backup script on the laptop" works with on-laptop approval.

## M5 — Plain-language rules and scripting
- `rules.propose` flow with diff UI; a Hermes skill that teaches the agent the rule schema.
- Sandboxed JS (QuickJS) for rule logic.
- **Exit:** five everyday automations created only by asking the agent, each approved on the device.

## M6 — Wearables
- Wear OS relay (`relayed` devices): notifications and haptics on the watch, quick-reply buttons.
- Glasses: one vendor SDK proof of concept through the phone relay.
- **Exit:** read an agent notification and reply by voice from the watch.

## M7 — iOS
- `iosApp`: chat, notifications, location and region triggers, camera, Shortcuts / App Intents.
- APNs wake via a self-hosted relay (decision pending; see ARCHITECTURE §8).
- **Exit:** TestFlight build used by at least one contributor for two weeks.

## Not in v1
- Multi-user bridges, device-to-device messaging, live audio/video streaming, a Play Store build with SMS, AI screen-tapping automation.

## Working agreements
- Every protocol change updates `spec/` first, with schema and test vectors, then code.
- Security-relevant changes (tiers, approvals, redaction) need a written rationale in the pull request.
- Releases: semantic versioning; signed APKs; changelog.
