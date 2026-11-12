# HearCopy

Native Android app (Kotlin). A floating button ("hearcopyTouch") captures a
short clip of audio playing on the device (for example TikTok) via
AudioPlaybackCapture, sends it to OpenAI `gpt-transcribe`, and shows
selectable, copyable text. Built for the owner's own use and published as
a portfolio project.

This file instructs the coding agent (Claude Code) that works in this
repository. Paths under `docs/private/` and `.claude/` are gitignored and
kept private: the owner and the agent have them locally; they are not in
this repository.

## Read order (every new session)

1. `docs/STATE.md` - where we are now. Read it first.
2. `docs/DECISIONS.md` - ADRs that bind the code.
3. `docs/private/HearCopy_project_kickoff.md` (private) - owner's product
   spec. READ-ONLY for you. Product reference, not the architecture of
   record (see the architecture rules below).
4. `docs/private/capN-design.md` (private) for the active Cap, if
   present - working notes for the current Cap. Lower priority than STATE,
   DECISIONS and the guards.

Source-of-truth priority: code + guard tests > STATE.md > DECISIONS.md > capN-design.md > kickoff.

## Fixed shape (do not change without owner approval + ADR)

- One Gradle module `:app`. minSdk 29, targetSdk latest stable.
- Package root: `com.dennycgan.hearcopy`
  Packages: `capture/ overlay/ transcription/ notify/ state/ data/ ui/`
  plus `AppContainer` (hand-written DI, no DI framework).
- Single state source: `CaptureStateStore` (StateFlow) with pure
  `reduce(state, event)`.
- The tech stack is closed. Dependencies are allowlisted in
  `gradle/libs.versions.toml` and enforced by guard tests. Never add a
  library, plugin, or SDK on your own.
- Repo root, `docs/`, packages, imports, dependencies, manifest and language
  are enforced by tests under
  `app/src/test/java/com/dennycgan/hearcopy/architecture/`.

## Commands (Git Bash, repo root)

- Unit + architecture tests: `./gradlew test`
- Lint: `./gradlew lint`
- Debug build: `./gradlew assembleDebug`
- Install on device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Device log: `adb logcat`

Run `./gradlew test` before presenting any diff that touches `app/` or `gradle/`.

## Language

- Code, comments, identifiers, commit messages, PR text, committed docs:
  English only. No Chinese (or any CJK) characters in any tracked file.
- User-visible copy: English, British spelling, only in
  `app/src/main/res/values/strings.xml`. No hard-coded UI strings.
- Reply to the owner in the owner's language. Chat language never leaks
  into code, copy or git history.

## Privacy and secrets (hard rules)

- Never read, print, copy or reference: `*.jks`, `*.keystore`,
  `keystore.properties`, any signing secrets, `.env`.
- Never write a real API key anywhere: code, tests, fixtures, logs,
  docs, commit messages. Tests use obvious fakes (e.g. `sk-test-fake`).
- Never put into committed files: real device model, serial number,
  build number or other device identifiers (use `Device-A`; the map is in
  `docs/private/device-codenames.md`, private), local absolute paths
  (e.g. `C:\Users\...`), or the owner's personal email.
- Naming TikTok as an example audio source is allowed.
- Nothing from `docs/private/` is ever copied into committed files.
- This file is committed and public: the rules above apply to it too.

## Owner-only actions (stop and ask the owner to do these)

- Creating the release keystore or changing signing config
- Creating or rotating OpenAI keys
- Changing repo visibility, branch protection, or GitHub settings
- Publishing a GitHub Release
- Adding or editing an ADR without the owner's approval of the draft in chat

## Rules

Detailed rules live in `.claude/rules/` (private, not in this repository):

- `workflow.md` - design first, scope, stop conditions, device verification
- `architecture.md` - guard tests and how architecture changes
- `docs.md` - STATE / DECISIONS / PHASE_HISTORY, debt, two commits
- `git.md` - commit format, two-step git gate, post-commit checks
