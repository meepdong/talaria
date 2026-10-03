# UI sketches (draft v0.1)

Low-fidelity layouts for the Android reference client. Desktop uses the same screens in a two-pane layout (chat list left, conversation right).

## 1. Pairing

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ Connect to your server       │   │ Confirm pairing              │
│                              │   │                              │
│  ┌────────────────────────┐  │   │ Check that your terminal     │
│  │      [ camera view ]   │  │   │ shows the same code:         │
│  │   point at the QR in   │  │   │                              │
│  │     your terminal      │  │   │        482 913               │
│  └────────────────────────┘  │   │       🦊  🌙  🎸              │
│                              │   │                              │
│  [ Paste a pairing link ]    │   │ Then type  y  in the         │
│  [ Enter a code ]            │   │ terminal to approve.         │
│                              │   │                              │
│  Needs Tailscale on ⓘ        │   │ Waiting… (1:43)   [Cancel]   │
└──────────────────────────────┘   └──────────────────────────────┘
```

## 2. Connection status (first checkpoint)

```
┌──────────────────────────────┐
│ ← Connection                 │
├──────────────────────────────┤
│ ● Network      Tailscale ok  │
│ ● Bridge       Connected 45ms│
│ ● Meep         Ready         │
│ ◐ Scout        Degraded      │
│   "Provider error: out of    │
│    credit" — top up ›        │
│ ○ Coder        Offline       │
├──────────────────────────────┤
│ Last connected  just now     │
│ Device          OnePlus 10 Pro│
│ [ Test connection ]          │
└──────────────────────────────┘
```
Failures name the broken layer and the fix: "Server unreachable — is Tailscale on?", "This device was revoked — pair again", "Server identity changed ⚠️ — do not continue unless you re-installed the bridge".

## 3. Chat list

```
┌──────────────────────────────┐
│ Talaria            🔍  ⋮     │
├──────────────────────────────┤
│ 🎩 Meep              21:04   │
│    Your 8am briefing is set  │
│ 🧠 Trip Planning (3)  20:51  │
│    Scout: Cheapest flights…  │
│ 🛠️ Coder             Yesterday│
│    Build passed ✅            │
│ 🔍 Scout              Mon    │
├──────────────────────────────┤
│                     [ ✎ New ]│  → New chat with… / New group
└──────────────────────────────┘
```

## 4. One-to-one chat

```
┌──────────────────────────────┐
│ ← 🎩 Meep · Sonnet 5.5   ⓘ   │  ← tap = agent profile
├──────────────────────────────┤
│        Summarise this PDF ▸  │
│        [📄 lease.pdf · 6 pp] │
│ Meep                         │
│ ⚙ reading lease.pdf…         │  ← tool progress
│ Key points: rent ₹18k, 11-mo │
│ lock-in, 2-month deposit…    │
│ 🔊                            │  ← read aloud
├──────────────────────────────┤
│ 📎  Message…          🎤  ➤  │
└──────────────────────────────┘
```
**Voice:** hold 🎤 to talk (or tap to toggle). Live transcript fills the box; edit, then send. Optional auto-send.
**Attach:** camera, photo, PDF, file. Images are downscaled and location data removed.

## 5. Group chat

```
┌────────────────────────────────────────┐
│ ←  🧠 Trip Planning        👥 3 agents │  ← tap header = Group info
├────────────────────────────────────────┤
│                     You: plan a 3-day  │
│                     Goa trip under ₹15k│
│ 🎩 Meep (conductor)                    │
│ On it. @Scout, find flights; …         │
│ 🔍 Scout                    · Haiku    │
│ Cheapest flights: ₹4,200 return…       │
│  ↳ replying to Meep                    │
│ 🎩 Meep                                │
│ Here's the full plan …  [itinerary.pdf]│
│ ▸ 3 workers · ₹2.10   (workflow view)  │
│ 🔍 Scout is typing…                    │
├────────────────────────────────────────┤
│ 📎  🎤  Message… (@ to mention)    ➤  │
└────────────────────────────────────────┘
```
Status ticks: ✓ sent · ✓✓ received · ⏳ working · ✅ done.

## 6. Group info

```
┌────────────────────────────────────────┐
│            🧠  Trip Planning            │
│   "Plan trips on a budget. Meep runs   │
│    the show, Scout researches."        │
├────────────────────────────────────────┤
│ Media, links and docs            12 ›  │
│ Spend in this group   ₹38 / ₹50 today › │
├────────────────────────────────────────┤
│ 3 participants                         │
│ 🎩 Meep      Conductor · Sonnet 5.5  ● │
│ 🔍 Scout     Researcher · Haiku 4.5  ● │
│ 🛠️ Coder     Builder · Opus 5.5      ○ │
│ ➕ Add agent                            │
├────────────────────────────────────────┤
│ Who replies:  ◉ Conductor decides       │
│               ○ Only when @mentioned    │
│               ○ Everyone (costly)       │
│ Max agent replies per message:   3     │
│ Daily budget:                 ₹50      │
└────────────────────────────────────────┘
```

## 7. Agent profile

```
┌────────────────────────────────────────┐
│               🔍  Scout                 │
│        Researcher · Haiku 4.5           │
│   [ Message privately ]  [ Edit soul ]  │  ← Edit only if enabled on the bridge
├────────────────────────────────────────┤
│ SOUL                                   │
│ "You're Scout: fast, thorough, cheap.  │
│  Find facts, cite sources, keep it     │
│  short. Never make bookings."          │
│                              Show all ›│
├────────────────────────────────────────┤
│ Can handle:  📝 text  🖼️ images         │
│ Tools:       web search · files        │
│ Memory:      "Prefers budget options…" ›│
│ Spend:       ₹6.20 this week           │
│ Model:       anthropic/claude-haiku-4.5│
└────────────────────────────────────────┘
```
**Edit soul** opens an editor and then a before/after diff with "Save as new version". Previous versions can be restored. A note warns that the next message will cost a little more, because the agent's cached prompt is rebuilt.

## 8. Workflow view

```
┌────────────────────────────────────────┐
│ ← Workflow · "Plan Goa trip"   ₹2.10   │
├────────────────────────────────────────┤
│ 🎩 Meep (conductor) · Sonnet   running │
│  ├─ ✅ Flights 14–17 Nov   Haiku ₹0.42 │
│  ├─ ⏳ Hotels under ₹3k    Haiku ₹0.31 │
│  └─ ⏸ Weather check       queued      │
├────────────────────────────────────────┤
│ Budget ₹10 · Workers 2/5 running       │
│ [ Cancel workflow ]                    │
└────────────────────────────────────────┘
```

## 9. Voice reply

```
┌──────────────────────────────┐
│ 🎤 "Reply to Asha on         │
│    WhatsApp: tell her I'll   │
│    be ten minutes late"      │
├──────────────────────────────┤
│ To:   Asha Mehta  (WhatsApp) │  ← tap to change; asks if ambiguous
│ ┌──────────────────────────┐ │
│ │ Running 10 mins late,    │ │  ← Polish mode (✏️ edit · ↺ exact words)
│ │ sorry!                   │ │
│ └──────────────────────────┘ │
│ Route: reply via notification│  ← or "opens WhatsApp — tap Send"
│                              │
│  [ Cancel ]      [ Send ✓ ]  │
│  🔊 "Send to Asha?" — say     │
│     "send", "change…", "stop"│
└──────────────────────────────┘
```
Entry points: in-app mic, Quick Settings tile, "🎤 Reply by voice" on Talaria's notifications, headset button, Talaria as default assistant (long-press power or home), and later watch and glasses.

Incoming read aloud (opt-in, per app): *"Asha on WhatsApp: 'Dinner tonight?' Reply?"* → speak the answer → the same confirmation card.

## 10. Assistant overlay

```
[ long-press power ]
┌──────────────────────────────┐
│     (whatever app is open)   │
│                              │
├──────────────────────────────┤
│ 🎩 Meep        ● listening…  │
│ "set a timer for 10 minutes" │
│ ✅ Timer set · 10:00   [Stop] │  ← handled on the phone, instantly
├──────────────────────────────┤
│ 🖼 Use screen?  ⌨ Type  ⤢ Open│  ← screen context only on tap, with preview
└──────────────────────────────┘
```
Agent answers stream into the same sheet and are spoken as they arrive. "⤢ Open" continues the conversation in the full app. On the lock screen, the sheet shows a 🔒 badge, and personal requests ask for an unlock.

## 11. Design notes
- Every agent has a fixed colour and avatar across chats, groups and workflows.
- A model badge on each agent message makes cost visible without being noisy.
- Spend is always one tap away: group info, agent profile, workflow header.
- Destructive or costly settings (round-robin replies, raising budgets, editing souls) show a one-line consequence before applying.
