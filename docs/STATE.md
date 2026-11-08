# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [~] Cap 1 Capture session + hearcopyTouch  <- YOU ARE HERE
  - Android 11: overlay permission guidance must not promise to open
    HearCopy's own settings page.
  - Android 11: check RECORD_AUDIO before every recording start.
- [ ] Cap 2 Transcription loop
  - Decide where the API key comes from before Cap 2 design (settings
    page is Cap 3).
- [ ] Cap 3 Main UI, settings and lifecycle completion
- [ ] V1 release
  - Includes Device-B (Android 11) full flow.
  - App icon (clears lint MissingApplicationIcon; icon vectors must use
    @color references or need a CopyAndThemeTest decision).

## Phase Roadmap (Cap 1 Capture session + hearcopyTouch)

- [x] 1.1 State model: CaptureState, pure reduce, tapAction,
  CaptureStateStore, JVM tests; coroutines libraries (6b0ff0f)
- [x] 1.2 Capture pipeline: AudioSource, CaptureSession, WavWriter,
  SilenceDetector, 300 s dual cap, 2 s Preparing timeout; JVM tests
  (4a0011a)
- [ ] 1.3 First real capture on Device-A: CaptureService, PlaybackCaptureSource,
  CaptureCommands, AppContainer wiring, notification channels, temporary
  main screen, manifest permissions, minimal hearcopyTouch, temp files and
  startup cleanup, debug-only WAV retention, diagnostics; plus
  CaptureSession hardening H1 (stale-session stop guard) and H2 (reader
  outcome guard)
- [ ] 1.4 hearcopyTouch complete: drag, timer, progress ring, Preparing
  ignore, Processing haptic, in-memory position
- [ ] 1.5 Lifecycle rules: back to background, Recents teardown, reopen
  reuse or re-request, authorisation lost (hide, alert, stop), single main
  screen instance, no duplicate button

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Open | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; affects `RepeatOnLifecycleWrongUsage` and `UnsafeLifecycleWhenUsage` | Cap 3 (re-check when lifecycle-runtime-compose is added) |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` (first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly | - |
