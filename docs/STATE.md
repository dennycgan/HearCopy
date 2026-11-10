# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [ ] Cap 2 Transcription loop  <- YOU ARE HERE
  - Decide where the API key comes from before Cap 2 design (settings
    page is Cap 3).
  - Minimum clip length 1.0 s, measured on the overlay timer clock
    (elapsedRealtime at the stop minus first audio): stopped while the
    timer shows 00:00 -> clip deleted, no upload, previous transcript
    kept, no Failed state, back to Ready. Worth an ADR at the Cap 2 work
    commit.
  - Verify the Processing reject haptic on Device-A (Cap 1 D1); the
    system touch-vibration setting must be on.
- [ ] Cap 3 Main UI, settings and lifecycle completion
  - Persist the hearcopyTouch position (DataStore, data/). The overlay
    reads and writes it only through state/ and CaptureCommands (Rule 4),
    never overlay -> data/.
- [ ] V1 release
  - Includes Device-B (Android 11) full flow, including: the overlay
    permission guidance does not promise HearCopy's own settings page;
    RECORD_AUDIO is re-checked at every recording start ('Only this
    time').
  - App icon (clears lint MissingApplicationIcon; icon vectors must use
    @color references or need a CopyAndThemeTest decision).
  - Keep or remove the debug-only WAV retention (debug builds keep the
    last three valid clips).
  - README discloses continuous playback capture during an active
    session; only audio after a tap is kept.

## Phase Roadmap (Cap 2 Transcription loop)

- Not scoped yet: Cap 2 phases await the owner's approved scope.

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Open | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; affects `RepeatOnLifecycleWrongUsage` and `UnsafeLifecycleWhenUsage` | Cap 3 (re-check when lifecycle-runtime-compose is added) |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` for every catalog library it reports (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
