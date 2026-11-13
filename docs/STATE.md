# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [x] Cap 2 Transcription loop
- [x] Public repo
- [~] Cap 3 Main UI, settings and lifecycle completion  <- YOU ARE HERE
- [ ] V1 release
  - Includes Device-B (Android 11) full flow, including: the overlay
    permission guidance does not promise HearCopy's own settings page;
    RECORD_AUDIO is re-checked at every recording start ('Only this
    time'); a dismissed microphone dialog can make the status card open
    app info instead of asking again (ADR-018).
  - Full-flow device paths moved from Cap 3 phase 3.2, not yet run on a
    device: the microphone status card when Android no longer asks,
    with the app info tap; the display-over-other-apps permission
    revoked during an upload, with the Finishing card (ADR-015,
    ADR-018).
  - App icon (clears lint MissingApplicationIcon; icon vectors must use
    @color references or need a CopyAndThemeTest decision).
  - Keep or remove the debug-only WAV retention (debug builds keep the
    last three valid clips).

## Phase Roadmap (Cap 3 Main UI, settings and lifecycle completion)

- [x] 3.1 hearcopyTouch position across restarts: stored on drag end
  in its own DataStore file, restored at process start through state/
  and CaptureCommands (ADR-017) (1381c97)
- [x] 3.2 Main screen and settings page (real key): header, status card
  that walks the setup steps, transcript with Copy all, settings page
  with key save, replace and delete; notification taps return to the
  main screen; lifecycle-runtime-compose; D-002 re-check (ADR-018)
  (13765c7)
- [ ] 3.3 Key setup and failure guidance (real key): clipboard cleared
  after a key save, Test transcription with a built-in clip, failure
  and alert texts that say what to do (D-006), InitFailed removed

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Accepted | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; the skipped checks (`RepeatOnLifecycleWrongUsage`, `UnsafeLifecycleWhenUsage`) cover `repeatOnLifecycle` and `Lifecycle.whenX`, which HearCopy does not call; declaring lifecycle-runtime-compose did not clear it | - |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` and `GradleDependency` for every catalog library they report (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test, lifecycle-runtime-compose; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly; lifecycle-runtime-compose is pinned at 2.9.4, the version already resolved (ADR-018) | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
| D-005 | Accepted | 2 | The KeyUnreadable alert can be silent: the transcript notification posted just before it uses up Android's per-app sound rate limit | ADR-014; the banner still shows | - |
| D-006 | Open | 2 | The NoAudio text says the clip was silent, although the source app may not allow capture | Blocked and silent sources both record as exact zeros, so the text must cover both causes | Cap 3 phase 3.3 (failure copy) |
