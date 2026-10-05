# Changelog

Talaria's releases (PROCESS.md "Releases" in the deployment notes): `X.Y.Z-beta.N` for the owner's devices first,
`X.Y.Z` for everyone once tested. Sections: Added, Changed, Fixed, Security. CI uses the version's section as the
release notes the app shows.

## [Unreleased]

### Changed
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
