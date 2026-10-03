# Talaria

> **Give your AI agent hands and senses on every device you own.**
>
> *Working name. In myth, the talaria are Hermes' winged sandals: they carry the messenger anywhere.*

**Status:** design draft (v0.1). No code yet. Feedback welcome.
**Not affiliated with Nous Research.** Talaria is an independent community project that works with [Hermes Agent](https://github.com/NousResearch/hermes-agent) and, by design, with any agent that speaks [MCP](https://modelcontextprotocol.io).

---

## What it is

Talaria turns your phone, laptop, and (later) watch or smart glasses into **device nodes** for a self-hosted AI agent. It has three parts:

| Part | What it does |
|---|---|
| **Talaria Bridge** | A small service that runs next to your agent (e.g. on your VPS). Devices connect to it; the agent talks to it through MCP tools. |
| **Talaria Node Protocol (TNP)** | An open, versioned protocol: devices announce their capabilities, receive commands, stream events, and request approvals. |
| **Talaria clients** | Cross-platform apps (Android first, then Windows/Linux, then iOS) that implement the protocol, plus chat, notifications, and an on-device automation engine. |

```
 Phone ─┐                                   ┌─ MCP tools ──▶ Hermes Agent (or any MCP agent)
 Laptop ─┼── TNP (WebSocket, Tailscale) ──▶ Bridge
 Watch* ─┘   (*relayed through the phone)    └─ webhooks ──▶ agent runs triggered by device events
```

## What you can do with it

- **Pair securely** by scanning a QR code in your terminal, or by pasting a link on devices without cameras, then confirming a matching code.
- **See connection health at a glance**: network, bridge, and each agent, with clear fixes when something breaks.
- **Chat** with your agent from a native app (replaces Telegram-as-UI), by text or **voice** (speech-to-text runs on the phone for free), with **photos, PDFs and files** attached.
- **Several agents, each with its own soul**: a personal assistant, a coder, a cheap researcher. Open any agent's profile to read its SOUL.md, model, tools and spend.
- **Group chats with your agents, WhatsApp-style**: a conductor agent hands tasks to others, with reply caps and daily budgets so it never runs away.
- **Watch multi-agent workflows live**: a strong conductor delegates to cheap worker models, and you see every worker's task, model, status and cost.
- **Reply to anyone by voice**: "Reply to Asha on WhatsApp: I'll be ten minutes late." Talaria confirms (on screen and out loud) and delivers through the messaging app itself. Hands-free when possible, one tap otherwise.
- **Notifications on your terms**: the agent pushes alerts into your own channels, priorities and sounds, with reply buttons.
- **Device-aware automation**: "when a bank SMS says *debited*, log the expense and tell me if I've spent over ₹2,000 today." Rules run on the device; the agent can *propose* rules in plain language, and you approve them.
- **Phone as a sensor**: location triggers, filtered notification forwarding, battery and connectivity state.
- **Desktop as an actuator**: run *pre-registered* scripts, watch folders, press a global hotkey to talk to the agent.
- **One bridge for future devices**: watches and smart glasses connect through the phone as relayed nodes.

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
| [docs/UI.md](docs/UI.md) | Screen sketches: pairing, status, chats, group info, agent profiles, workflow view |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Milestones with exit criteria (pairing → status → chat → multi-agent → groups → workflows → device features) |

## License

Planned: **Apache-2.0**. It is compatible with the MIT and Apache-2.0 projects this design learns from, and includes a patent grant.
