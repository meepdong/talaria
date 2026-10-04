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
│  Needs your VPN on ⓘ         │   │ Waiting… (1:43)   [Cancel]   │
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
Failures name the broken layer and the fix: "Server unreachable — is your private network (Tailscale/WireGuard) on?", "This device was revoked — pair again", "Server identity changed ⚠️ — do not continue unless you re-installed the bridge".

## 2a. Home and menu bar (M2)

```
┌──────────┬───────────────────────────────────────────┐
│ Talaria  │ Monday 5 October                      ☰  │
│          │ Today                                     │
│ ▣ Home   │ ┌ Your day ─────────────────────────────┐ │
│ ▢ Chats  │ │ from the 10:00 catch-up · calendar    │ │
│ ▢ Files  │ └───────────────────────────────────────┘ │
│ ▢ Sched. │ ┌ To do ────────┐ ┌ Next up ────────────┐ │
│          │ │ ☐ …           │ │ 11:30 Summary  auto │ │
│          │ └───────────────┘ │ 14:00 Meeting  cal  │ │
│          │                   └─────────────────────┘ │
│          │        [ Chat with Hermes ]  [ 🎤 Talk ]  │
└──────────┴───────────────────────────────────────────┘
```
Paired apps open on Home. The menu bar runs down the side on a wide window and along the bottom on a phone; a phone hides it while a conversation is open. ☰ opens a panel with what's running now, provider balances with Add credits, and the connection rows, linking to the full connection page (§2). Chat starts a new chat; Talk starts one by voice: what's heard is sent at once and the answer is read aloud. Design mockup: the Talaria redesign canvas (Home, Files, Schedule).

**To do** (spec/README.md §13) is a short list kept on the bridge, so the phone and the laptop show the same one. Add a line, tick it off, or tap **Ask Hermes** to start a chat asking Hermes to do it. The to-do then shows "With Hermes…" while Hermes is replying, and "Open chat" afterwards. Ticked items stay until the end of the day; the older ones are counted.

**Your day, Next up and Automations on** (spec/README.md §14). Your day shows today's results from automations that report to Home, such as the morning summary of the catch-up call. Next up lists the next calendar events and automation runs with how long until each ("in 25 min"). Automations on lists what's switched on and when it next works.

## 2a′. Schedule (M2)

Today's calendar, read through the agent, next to the agent's automations. Each automation shows when it runs, when it next works, how its last run went and where its result goes. It has a switch to pause it, **Run now** and **Delete**. When its last run was blocked because it needed an approval, it also has **Run in chat**. Jobs Hermes made in any chat are listed too, marked "Made by Hermes".

**New automation** offers two ways in. **Describe it**: type what you want ("every weekday at 8, summarise my unread email") and Hermes sets it up with its own scheduler. **Set it up yourself**: a name; when (at a time, when something arrives, or after a calendar event); what Hermes should do; and where the result goes (Home, a new chat, or the log only). **Morning catch-up summary** fills the form for the weekday summary of the company call: it watches 10:30–13:00 for the Gemini notes email, and summarises from the calendar alone if the email hasn't come by 13:00.

## 2b. Files (M2)

The folders the bridge shares (spec/README.md §12): what was sent from Talaria, and whatever the owner adds in `agents.json`, such as Hermes's workspace. A chip per folder, then the folder's files, newest first, with a search by name. Tapping a folder opens it; **Open** fetches a file and hands it to the device's own viewer (a desktop refuses program files); **Ask Hermes** starts a chat with that file attached by name, so the agent reads it where it is and nothing is uploaded.

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
**Composer:** one rounded field with 📎 on the left and 🎤 on the right inside it, and a round ➤ beside it, so the text gets the width on a phone.
**Approvals:** when Hermes wants to run something it flags as risky, the reply shows a card with the command, why it was flagged, and Allow once / Allow for this chat / Always allow / Deny (only the answers Hermes offers). Any paired device can answer; the card goes away on all of them.
**Blocked automations:** a scheduled run has nobody to approve anything, so Hermes refuses risky commands in it. Such a run shows in Your day on Home, whatever its result setting, as "Blocked: Hermes needed your approval for …" with a **Run in chat** button, and notifies once. Run in chat opens a new chat that runs the automation's task; its approval card appears there, and Always allow lets future scheduled runs do the same.
**Model:** the chip in the header opens a dropdown of the models Hermes has keys for, grouped by provider, with a search box at the top. `/model name` picks a model directly when one matches, and opens the dropdown filtered to the matches when several do.

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

## 11. Schedules

```
┌────────────────────────────────────────┐
│ ← Schedules                        ＋  │
├────────────────────────────────────────┤
│ ⏰ Weekday alarm   06:30   📱 Phone     │
│ 🌙 DND on          22:00   📱 Phone     │
│ ☀️ Morning brief   08:00   ☁️ Server    │
│     Meep · Haiku · ~₹0.40/run          │
│     last run ✅ 08:00 · ₹0.38           │
│ 📊 Weekly spend    Sun 20:00 ☁️ Server  │
├────────────────────────────────────────┤
│ Runs on: decided automatically ⓘ       │
└────────────────────────────────────────┘
```
Creating a schedule (typed or spoken) shows where it will run and why ("Needs your agent, so it runs on your server, even when your phone is off"), with "Run on phone instead / Run on server instead" when both are possible. Server schedules show agent, model, cost per run, last result and a "Run now" button.

## 12. Design notes
- Every agent has a fixed colour and avatar across chats, groups and workflows.
- A model badge on each agent message makes cost visible without being noisy.
- Spend is always one tap away: group info, agent profile, workflow header.
- Destructive or costly settings (round-robin replies, raising budgets, editing souls) show a one-line consequence before applying.
