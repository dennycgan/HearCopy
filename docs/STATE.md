# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [ ] Cap 1 Capture session + hearcopyTouch  <- YOU ARE HERE
  - Android 11: overlay permission guidance must not promise to open
    HearCopy's own settings page.
  - Android 11: check RECORD_AUDIO before every recording start.
- [ ] Cap 2 Transcription loop
- [ ] Cap 3 Main UI, settings and lifecycle completion
- [ ] V1 release
  - Includes Device-B (Android 11) full flow.
  - App icon (clears lint MissingApplicationIcon; icon vectors must use
    @color references or need a CopyAndThemeTest decision).

## Phase Roadmap (Cap 1 Capture session + hearcopyTouch)

- Not scoped yet: Cap 1 phases await the owner's approved scope.

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Open | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; affects `RepeatOnLifecycleWrongUsage` and `UnsafeLifecycleWhenUsage` | Cap 3 (re-check when lifecycle-runtime-compose is added) |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` (first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly | - |
