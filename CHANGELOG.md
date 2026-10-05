# Changelog

Talaria's releases (PROCESS.md "Releases" in the deployment notes): `X.Y.Z-beta.N` for the owner's devices first,
`X.Y.Z` for everyone once tested. Sections: Added, Changed, Fixed, Security. CI uses the version's section as the
release notes the app shows.

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
