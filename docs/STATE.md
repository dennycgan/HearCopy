# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [x] Cap 2 Transcription loop
- [ ] Public repo  <- YOU ARE HERE
  - README discloses continuous playback capture during an active
    session; only audio after a tap is kept.
  - README lists which apps cannot be captured and why; the check is to
    try a short clip.
- [ ] Cap 3 Main UI, settings and lifecycle completion
  - Persist the hearcopyTouch position (DataStore, data/). The overlay
    reads and writes it only through state/ and CaptureCommands (Rule 4),
    never overlay -> data/.
  - Key setup: clear the clipboard after a key save, a test
    transcription button, failure texts that say what to do (with
    D-006).
- [ ] V1 release
  - Includes Device-B (Android 11) full flow, including: the overlay
    permission guidance does not promise HearCopy's own settings page;
    RECORD_AUDIO is re-checked at every recording start ('Only this
    time').
  - App icon (clears lint MissingApplicationIcon; icon vectors must use
    @color references or need a CopyAndThemeTest decision).
  - Keep or remove the debug-only WAV retention (debug builds keep the
    last three valid clips).

## Phase Roadmap (Public repo)

- [x] Full-history privacy audit: keys, personal email, local absolute
  paths, real device model names, private notes never committed; no
  finding up to e779446; a finding is reported, never fixed by
  rewriting history
- [x] LICENSE: MIT (ffa31cc)
- [x] CLAUDE.md publication: reviewed for public reading, tracked and
  added to the RepoLayoutTest root allowlist; .claude/ stays private
  (ADR-016) (683ee0e)
- [ ] README: status, build, security (key storage, where the key and
  audio are sent, key guidance in three tiers), continuous capture
  disclosure, capture limits, "How this was built"
- [ ] Audit of the commits made after the full-history audit
- [ ] Visibility switch (owner)
- [ ] GitHub secret scanning and push protection (owner; right after
  the switch if not offered while private)
- [ ] Outside check while logged out

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Open | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; affects `RepeatOnLifecycleWrongUsage` and `UnsafeLifecycleWhenUsage` | Cap 3 (re-check when lifecycle-runtime-compose is added) |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` for every catalog library it reports (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
| D-005 | Accepted | 2 | The KeyUnreadable alert can be silent: the transcript notification posted just before it uses up Android's per-app sound rate limit | ADR-014; the banner still shows | - |
| D-006 | Open | 2 | The NoAudio text says the clip was silent, although the source app may not allow capture | Blocked and silent sources both record as exact zeros, so the text must cover both causes | Cap 3 (failure copy) |
