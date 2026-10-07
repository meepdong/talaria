# Changelog

Talaria's releases (PROCESS.md "Releases" in the deployment notes): `X.Y.Z-beta.N` for the owner's devices first,
`X.Y.Z` for everyone once tested. Sections: Added, Changed, Fixed, Security. CI uses the version's section as the
release notes the app shows.

## [Unreleased]

### Added
- Bots' board: the To-dos tab has "My list" and "Bots' board", Hermes's Kanban board (the same one Hermes Desktop
  shows). Add a task and pick a bot ("Add and start"): the bot works on it by itself and its notes show on the
  card. Tap a task to see its details, move it, give it to another bot or comment on it. The board updates by itself
  while it's open.
- Schedule tab: "Bots' routines", the tasks your bots do on a schedule (Hermes's own scheduled jobs; Hermes Desktop
  shows the same). Turn one off or on, run it now, delete it, or add one: pick a bot, a name, when ("weekdays at 9am",
  "every 2h", "every monday 8am") and what to do. Its results go to the bot's chat.
- Bot chats: while a bot works, the helper agents it starts show above the message box, with what each is doing;
  give one a note or stop it.
- Server page: "Hermes's usage", what Hermes spent over the last 7 or 30 days (all of it: chats, Talk, bots,
  automations, Hermes Desktop), day by day and by model.
- Hermes's own commands work in Talaria: in a chat with Hermes or a bot, type / to see Talaria's commands and then
  Hermes's (/help, /usage, /insights, /compress, /kanban list, /skills, skill commands such as /weekly-review…).
  What they print shows as a card in the chat; skill commands become a message the bot answers. Commands that
  change Hermes's settings (/yolo, /personality, /reload, /kanban create…) ask first, with the usual approval card.

### Fixed
- The chat header on the phone shows the chat's or bot's name again (it was squeezed to "…" by the connection
  text and the model chip): the connection is a dot, the model a small line under the name.

## [0.2.0-beta.21] - 2026-10-07

### Added
- Group chats: Hermes's rooms (two to six bots, and your own assistant) are in Chats under "Group chats", with a
  "needs you" mark when a member asks you (@user) or waits for an approval. Open one to see who said what, reply in a
  thread (tap a message), @mention members (or @all), answer approvals and Stop. "+ New group" makes one: a name and
  2–6 members. Rooms run on the server, so they keep going when the phone is off; rooms made on the laptop show too.
- Hermes can hand jobs to your bots too, not only Tally in Talk: when you type a request that fits a bot (mail,
  calendar, Drive, sheets, PDFs, pictures, research, meetings, vendors), Hermes gives it the job, gets the report and
  answers you itself; the job shows as a card in that bot's chat.

### Fixed
- Server page, Hermes's skills: switching now only marks a change; an Apply button sends all of them as one
  approval and one Hermes restart (it used to restart Hermes for every switch, and a switch tapped again while it
  applied asked again). While it waits for approval or applies, the switches are locked and say so.
- Bots showed their profile name ("chainmail") instead of their title ("ChainMail").
- Opening a bot made in Hermes Desktop failed ("The bot's chat isn't available"): Talaria now finds the bot's existing
  chat the way Hermes reports it.

## [0.2.0-beta.20] - 2026-10-06

### Changed
- Talk: Tally leads. The voice is Tally (her name and manner) and she does all the talking; Hermes and your bots are
  her workers. She gives them jobs (several at once if needed), hands them files you sent in the chat by name,
  checks on them, adds to a running job ("also check tomorrow") or stops it, and tells you each result in her own
  words when it comes. In the chat each job is one folded card ("🔧 Hermes · done"): tap it for the brief and the
  report; an approval it needs shows on the card.

### Fixed
- Replies in a bot's chat were labelled "Hermes"; they carry the bot's name now.

## [0.2.0-beta.19] - 2026-10-06

### Added
- Hermes compatibility check: when Hermes updates itself (and once a day), the bridge checks every Hermes call
  Talaria relies on. If an update changed one, a red card on Home ("Hermes compatibility check") and a
  notification say what changed and which feature it affects; the card goes away once a check passes again.
  By hand: `talaria doctor --hermes`.
- The bridge is a doorway to Hermes's other backend (`hermes serve`, what Hermes Desktop uses): bots, group chats,
  jobs, skills and helper agents, through an allowlist (never shell commands, settings, secrets or the vault),
  with Hermes's approvals and questions passed to your devices. Bots are the first part you can see (below);
  group chats come next. The compatibility check covers it too. Server side: `hermes serve` runs on this machine
  only (`talaria-hermes-serve`, bridge/deploy).
- Bots: the bots you make in Hermes Desktop (its Bots tab) show at the top of Chats. Tap one to open its
  permanent chat (the same one Hermes Desktop shows), with replies, tool progress, Stop, notes and approvals as
  with Hermes. Type @ in any chat to pick a bot: "@scout find a quiet cafe" sends that to Scout's chat and opens
  it. In Talk, say "ask Scout to …": the voice sends it and tells you Scout's answer when it comes. Renaming or
  deleting a bot's chat in Talaria never touches it in Hermes.

### Fixed
- Picking a /command (or now an @bot) in the composer put the cursor back where it was, so what you typed next
  landed in the middle of the word.

## [0.2.0-beta.18] - 2026-10-06

### Added
- Talk answers sooner: at your first short pause it starts working on the answer, and if you keep talking it drops
  that and listens on. Nothing is said, done or saved until you've really finished.
- While the voice goes quiet to do something (add a to-do, check your agenda, brief Hermes), it says "Mm-hm, one sec."
- Talk voice: pick the voice in the chat's ⋮ menu (13 voices, including the newer, more natural Marin and Cedar),
  hearing each one first. The pick applies on every device.
- Server page: Hermes's skills as switches. Turning one off makes replies quicker and cheaper; a change restarts
  Hermes after you approve it.
- "Tidying up this long chat…" shows when Hermes is likely compressing a long chat before it answers.

### Changed
- What you say in Talk is written down by Whisper Large v3 Turbo (a seventh of the cost, about as fast, spells names
  from your to-dos), falling back to the voice model if Whisper isn't available.

## [0.2.0-beta.17] - 2026-10-06

### Added
- Talk keeps the conversation in the chat: what you said (🎙, written down as you said it, shown in the Talk bar
  within about two seconds) and what the voice answered. Talk with no chat open starts one, named after your first
  words, and opens it.
- A level bar in the Talk bar moves with your voice, so you can see it's hearing you.
- "Talk waits" in the chat menu: Quick (half a second of quiet ends your turn), Normal (0.8 s) or Patient (1.5 s).

## [0.2.0-beta.16] - 2026-10-06

### Added
- Talk 3: a voice model that hears you directly (GPT Audio Mini) answers out loud in a couple of seconds. It does
  quick things itself (add or tick off to-dos, today's or tomorrow's agenda) and reads them back, hands anything
  bigger to Hermes with a written brief, and tells you Hermes's answer in the same voice when it comes. One voice
  throughout; approvals as before (a spoken yes, or on screen; unlock first when locked). Talk falls back to the
  previous way where the bridge has no voice model.

## [0.2.0-beta.15] - 2026-10-06

### Fixed
- Talk kept reading an approval's question after Allow (or Deny) was tapped; it now stops at once and carries on.

## [0.2.0-beta.14] - 2026-10-06

### Added
- Talk keeps going with the screen off: once started, it listens and speaks in your pocket, with "Talking with
  Hermes" and End in the notification.
- Talaria can be the phone's default assistant (Settings › Apps › Default apps › Digital assistant app): a long
  press on the power button, the assistant gesture or a headset's voice button starts Talk in the last chat, also
  on the lock screen, where only the Talk panel shows (Unlock for the chats).
- An approval while the phone is locked waits: Talk says to unlock and approve, and carries on after.
- A short two-note tone when Talk opens the mic again, so you know it's your turn.

## [0.2.0-beta.13] - 2026-10-06

### Fixed
- The mic in a new chat started Talk in the last chat (the one Home's Talk uses) instead of the chat on screen.

## [0.2.0-beta.12] - 2026-10-06

### Added
- Approve by voice in Talk: Talk says what Hermes wants to do; "yes" allows it once, "no" stops it, and you can
  still answer on screen. "Always" stays an on-screen choice, and server operations still need a tap.
- The mic in a chat starts Talk in that chat; a long press dictates into the box as before.

### Changed
- Talk from Home continues the chat last open, whatever its age (long chats are compressed now).
- Talk stays on through an approval and keeps its voice; Read aloud uses the same natural voice. The phone's own
  voice is no longer used where the natural voice can play: a piece that can't be fetched is skipped.
- The quick first line is only said when Hermes takes more than about a second to start.
- The model picker lists only the models your OpenRouter key may use.

### Fixed
- The status bar was unreadable (white on white); it now takes the app's background in light and dark mode.

## [0.2.0-beta.11] - 2026-10-06

### Added
- Talk has a natural voice: Hermes's replies are spoken by a neural voice (Gemini 3.8 Flash-Lite TTS, voice
  Despina) instead of the phone's own, fetched sentence by sentence and played without gaps. The phone's voice
  takes over if the voice service can't be reached.
- Talk answers within a second or two: a quick line ("Sure, checking your calendar.") from a fast model while
  Hermes starts, Hermes says what it's doing before using a tool, and "Still working on it." after 8 s of quiet.
- Talk continues the last chat only if it was active in the last hour, so old long chats don't slow it down.
  Spoken messages show 🎙 in the chat.

## [0.2.0-beta.10] - 2026-10-06

### Added
- Talk is a conversation now: Hermes's answer is spoken sentence by sentence as it arrives instead of after it
  ends, it says what it's doing when it starts a tool ("Checking your calendar."), and the mic opens again when it
  has finished, so you can keep talking without touching the phone. Stay quiet to finish. While it thinks or
  speaks, tap the Talk bar to interrupt; End stops Talk.

## [0.2.0-beta.9] - 2026-10-06

### Fixed
- Swiping right (mark read, pin, done) left the row stuck half open and flipped read and unread over and over.
  Each swipe now acts once, as the finger lifts, and the row springs back.

## [0.2.0-beta.8] - 2026-10-06

### Added
- Swipes wherever things pile up: on Home's Your day and Needs you, swipe left to archive and right to mark read
  or unread; on chats, left archives (under Archived at the end of the list) and right pins; on to-dos, right ticks
  off and left deletes; on server results, left clears. Undo shows for 5 seconds after each. Long-press menus and
  screen readers offer the same actions.
- Your day shows each result as two lines with a dot while unread; tap one for the whole result, with Ask Hermes
  about this, Open chat and Copy. Its ⋮ has Mark all read, Archive read and Archived (restore anything archived in
  the last 30 days). Read and archived carry across devices, and take the phone's notification with them;
  swiping a notification away marks the result read.
- Chat with Hermes and Talk go back to the chat you were last in, with everything Hermes knows from it; `/new` or
  the + in a chat's header starts a new one.
- Ask Hermes about a result, a server action, or a to-do now tells Hermes what it is: what ran and when, what it
  said or why it failed, the to-do's due date and list. Run in chat says what the last run was blocked from.

## [0.2.0-beta.7] - 2026-10-05

### Fixed
- A message sent while the app reconnected could show twice.
- Automation results on Home show their formatting (bold, lists) instead of raw Markdown; long ones fold with
  Show more.

## [0.2.0-beta.6] - 2026-10-05

### Changed
- Terminals, second version: one approval opens a session full screen (system bars hidden), fitted to the width with
  pinch to zoom; tap the screen to type straight into it; a key row with sticky Ctrl and Alt, arrows, PgUp/PgDn,
  Home/End and F1–F12; swipe up for earlier lines (in Claude Code and other full-screen programs, a swipe pages them).
- New session (any name, folder and command, with Shell / Claude Code / opencode shortcuts) and End session (long-press).
- Terminal approvals ask for your fingerprint or screen lock, again after 5 minutes away from the app.

## [0.2.0-beta.5] - 2026-10-05

### Added
- Hermes can send you files in a chat (its new send_file tool): a PDF, image or document it made shows up in the
  conversation with Open, on every device, and stays in the chat's history.
- Files up to 2 GB, both ways: uploads and downloads stream from and to storage instead of memory, with
  "Uploading 40%" on the message and "Opening 40%" on the file. The server keeps at least 10 GB free.

## [0.2.0-beta.4] - 2026-10-05

### Added
- Terminals (☰ → Terminals): follow and answer the agent sessions in root's tmux (Claude Code, opencode) from the
  phone. Watching needs one approval; typing needs a second (it acts as root). Keys for agent prompts: ⏎, 1–3, y/n,
  Esc, Ctrl-C, arrows. Hermes can never open terminals (spec §16.1).

### Changed
- Every file you send, photos included, is saved to Hermes's inbox, so its tools can use it (e.g. put a signature
  image on a PDF). Photos are still shown to Hermes as pictures too.
- Update says "Installing…" as soon as it's tapped, through the download, until Android answers.

## [0.2.0-beta.3] - 2026-10-05

### Fixed
- Update asks for the "install apps" permission before downloading, carries on by itself when you come back,
  never downloads the same update twice, and shows "Installing…" until Android answers.

## [0.2.0-beta.2] - 2026-10-05

### Changed
- The first update installed from inside the app: nothing else changes.

## [0.2.0-beta.1] - 2026-10-05

### Added
- In-app updates: Talaria checks the bridge for a newer release and installs it with one tap (spec §17).
- Home: dismiss a "Needs you" item; long-press a tile's title to rearrange Home.

### Changed
- A message can carry up to 128 files. More than 10 photos reach Hermes as files.

### Security
- Releases are signed on the server with a private release key; the public debug key signs only debug builds,
  which now install as a separate "Talaria Debug" app.
