# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [~] Cap 2 Transcription loop  <- YOU ARE HERE
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

- [x] 2.1 Minimum clip length: TapStop stop time, discard below 1.0 s
  without upload (ADR-010) (6b38170)
- [x] 2.2 Transcription client: OkHttp request, JSON parsing, error
  mapping, no silent retry, key hygiene; JVM tests only (ADR-011,
  ADR-012) (b9517a2)
- [ ] 2.3 API key: Keystore AES-GCM storage in DataStore, temporary key
  field, key as a start condition, KeyRemoved / KeyUnreadable
- [ ] 2.4 Transcription loop on Device-A (first real-key phase): upload
  after save, Completed, transcript persistence and display,
  notification, Processing haptic
- [ ] 2.5 Lifecycle with a transcription in flight: SessionLost,
  deferred stop, Recents cancel, consent after the deferred stop

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Open | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; affects `RepeatOnLifecycleWrongUsage` and `UnsafeLifecycleWhenUsage` | Cap 3 (re-check when lifecycle-runtime-compose is added) |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` for every catalog library it reports (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
