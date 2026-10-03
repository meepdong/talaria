<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/mark-brass.svg">
    <img src="docs/brand/mark-ink.svg" alt="Talaria heel-wing logo" width="96">
  </picture>
</p>

# Talaria

> **Give your AI agent hands and senses on every device you own.**
>
> *Working name. In myth, the talaria are Hermes' winged sandals: they carry the messenger anywhere.*

[![tests](https://github.com/meepdong/talaria/actions/workflows/tests.yml/badge.svg)](https://github.com/meepdong/talaria/actions/workflows/tests.yml)

**Status:** design draft, **v1 scope defined** (see below). The bridge and secure pairing (M0) are built and tested — see [docs/M0-plan.md](docs/M0-plan.md) and [bridge/README.md](bridge/README.md) to try them. The Android and desktop clients are being built together in M1, starting with the shared protocol code in [client/](client/README.md). Feedback welcome.
**Not affiliated with Nous Research.** Talaria is an independent community project that works with [Hermes Agent](https://github.com/NousResearch/hermes-agent) and, by design, with any agent that speaks [MCP](https://modelcontextprotocol.io).

---

## What it is

Talaria turns your phone, laptop, and (later) watch or smart glasses into **device nodes** for a self-hosted AI agent. It has three parts:

| Part | What it does |
|---|---|
| **Talaria Bridge** | A small service that runs next to your agent (e.g. on your VPS). Devices connect to it; the agent talks to it through MCP tools. |
| **Talaria Node Protocol (TNP)** | An open, versioned protocol: devices announce their capabilities, receive commands, stream events, and request approvals. |
| **Talaria clients** | Cross-platform apps (Android and Windows/Linux together, iOS after v1) that implement the protocol, plus chat, notifications, and an on-device automation engine. |

```
 Phone ─┐                                   ┌─ MCP tools ──▶ Hermes Agent (or any MCP agent)
 Laptop ─┼── TNP (WebSocket, Tailscale) ──▶ Bridge
 Watch* ─┘   (*relayed through the phone)    └─ webhooks ──▶ agent runs triggered by device events
```

## v1: what you get first

v1 is deliberately small: **"your own Gemini, built on your own agent."** It's an Android and Windows/Linux app that replaces Telegram as the way you talk to your Hermes agent, and makes that agent your phone's assistant.

- **Pair securely** by scanning a QR code in your terminal, or by pasting a link on devices without cameras, then confirming a matching code.
- **See connection health at a glance**: network, bridge and agent, with clear fixes when something breaks.
- **Chat** with your agent by text or **voice** (speech-to-text runs on the phone for free), with **photos, PDFs and files** attached and spoken replies.
- **Make it your phone's default assistant**: long-press the power button and talk. Phone commands (timers, calls, apps) run instantly on the device and work offline; everything else goes to your agent.
- **Reply to anyone by voice**: "Reply to Asha on WhatsApp: I'll be ten minutes late." Talaria confirms (on screen and out loud) and delivers through the messaging app itself.
- **Notifications on your terms**: the agent pushes alerts into your own channels, priorities and sounds, with reply buttons.

See [ROADMAP.md](docs/ROADMAP.md#v1-scope-your-own-gemini-built-on-your-own-agent) for the v1 milestones and when v1 counts as done.

## Later: designed, not committed

These are designed so v1 doesn't paint us into a corner. They get built only after v1 is in real use, in the order users ask for them.

- **Several agents, each with its own soul**: open any agent's profile to read its SOUL.md, model, tools and spend.
- **Group chats with your agents, WhatsApp-style**: a conductor agent hands tasks to others, with reply caps and daily budgets.
- **Multi-agent workflows**: a strong conductor delegates to cheap worker models, with every worker's task, status and cost visible.
- **Schedules**: one list for phone-side schedules (alarms, DND) and server-side ones (briefings, summaries) that run even when your phone is off.
- **Device-aware automation and phone data**: rules on the device, filtered notification and SMS forwarding (e.g. automatic UPI expense logging), hands-free voice replies.
- **Desktop as an actuator**: run *pre-registered* scripts, watch folders, press a global hotkey to talk to the agent.
- **Wearables, car and iOS**: watch, Android Auto, and an iPhone client.
- **Hardware side quests**: a no-mic [smart ring](docs/hardware/RING.md) and a gesture [wristband](docs/hardware/BAND.md) as triggers and haptic displays.

## Why

Hermes users have asked for this repeatedly:
[#118010](https://github.com/NousResearch/hermes-agent/issues/118010) (first-class device nodes),
[#126292](https://github.com/NousResearch/hermes-agent/issues/126292) (native apps with location, approvals, voice),
[#75428](https://github.com/NousResearch/hermes-agent/issues/75428),
[#11911](https://github.com/NousResearch/hermes-agent/issues/11911),
[#60124](https://github.com/NousResearch/hermes-agent/issues/60124),
[#75512](https://github.com/NousResearch/hermes-agent/issues/75512).
All are open and marked *needs-decision*.

The existing community apps ([hermes-android](https://github.com/rusty4444/hermes-android), [Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile)) are good **chat and management clients**. None of them turn the device into a node the agent can sense and act through. Open-source automation apps such as [OpenTasker](https://github.com/SysAdminDoc/OpenTasker) have no agent integration. OpenClaw ships device nodes for its own agent, and its reviews show the hard part is **reliability and pairing**. Talaria is designed around both lessons.

## What it is *not*

- **Not only a chat app.** Chat is included, but the node and automation layers are the point.
- **Not an AI that taps your screen.** Projects like mobilerun already do that. Talaria exposes *typed, permissioned capabilities* instead.
- **Not a cloud service.** You run the bridge yourself. There is no Talaria account, server, or telemetry.

## Design principles

1. **The device is the authority.** Every permission decision is made and enforced on the device. A compromised agent or bridge cannot bypass it.
2. **Least data.** Devices filter and redact before anything leaves them. OTPs are never forwarded.
3. **A stable agent interface.** A small, fixed set of MCP tools, so connecting a new device never changes the agent's tool list (this keeps prompt caching effective).
4. **Agent-agnostic.** Hermes is the first target, but the bridge speaks MCP, so any MCP-capable agent can use it.
5. **Reliable before rich.** Pairing, reconnection and battery handling come before features.
6. **One protocol, many clients.** Platforms implement whatever capabilities they can, and the protocol says so honestly.

## Documents

| Doc | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, data flows, Hermes integration, repo layout, tech stack |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Talaria Node Protocol v0.1 draft: pairing, auth, messages, capabilities, rules, errors |
| [docs/CAPABILITIES.md](docs/CAPABILITIES.md) | Capability catalog and per-platform support matrix |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, permission tiers, privacy controls |
| [docs/hardware/RING.md](docs/hardware/RING.md) | Hardware track: a no-mic smart ring (based on Open Ring) as Talaria's trigger and haptic display; multi-device use |
| [docs/hardware/BAND.md](docs/hardware/BAND.md) | Hardware track: a gesture wristband (IMU first, optional sEMG) for push-to-talk, navigation and glasses control |
| [docs/UI.md](docs/UI.md) | Screen sketches: pairing, status, chats, group info, agent profiles, workflow view |
| [docs/ROADMAP.md](docs/ROADMAP.md) | v1 scope and milestones with exit criteria, plus everything planned for later |
| [docs/M0-plan.md](docs/M0-plan.md) | M0 (bridge and secure pairing): deliverables, exit criteria, status and what was verified |

## License

**Apache-2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE). It is compatible with the MIT and Apache-2.0 projects this design learns from, and includes a patent grant.
