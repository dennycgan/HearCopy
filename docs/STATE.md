# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [x] Cap 2 Transcription loop
- [x] Public repo
- [x] Cap 3 Main UI, settings and lifecycle completion
- [~] V1 release  <- YOU ARE HERE
  - Device-B (Android 11): covered by V1.4's release install and clip on
    Device-B (setup, 48 kHz capture, a transcription); the rest of the
    Device-B full flow and the two paths moved from Cap 3 phase 3.2 are
    dropped by owner decision.
  - Keep or remove the debug-only WAV retention (debug builds keep the
    last three valid clips).

## Phase Roadmap (V1 release)

- [x] V1.1 Debug side by side and app icon: debug builds install as
  com.dennycgan.hearcopy.debug; adaptive launcher icon, a waveform on
  the accent; manifest android:icon (ADR-020) (481c920)
- [x] V1.2 Device-B full flow: closed by owner decision without a
  device run; nothing was installed or run; the 48 kHz check moves to
  V1.4, the README tested-on line to V1.5
- [ ] V1.3 Release build (Device-A, release APK, real key): CLAUDE.md
  rules edit for the signed build; release signing that fails closed
  without the signing file; R8 without obfuscation; version 1.0.0;
  CHANGELOG.md; third-party notices; R8 task in CI; signed build by the
  owner in a separate clone; README release-build paragraph
- [ ] V1.4 Release candidate on the public path (Device-B, real key):
  signed build from a GitHub clone at the pushed head; immutable
  releases on; draft release with the APK; install from the draft in
  Chrome; setup and a clip, with the 48 kHz log check
- [ ] V1.5 Publish and close: publish the draft unchanged; asset digest
  check; README tested-on line, download, install and licence sections

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Accepted | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; the skipped checks (`RepeatOnLifecycleWrongUsage`, `UnsafeLifecycleWhenUsage`) cover `repeatOnLifecycle` and `Lifecycle.whenX`, which HearCopy does not call; declaring lifecycle-runtime-compose did not clear it | - |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` and `GradleDependency` for every catalog library they report (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test, lifecycle-runtime-compose; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly; lifecycle-runtime-compose is pinned at 2.9.4, the version already resolved (ADR-018) | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
| D-005 | Accepted | 2 | The KeyUnreadable alert can be silent: the transcript notification posted just before it uses up Android's per-app sound rate limit | ADR-014; the banner still shows | - |
| D-006 | Closed | 2 | The NoAudio text says the clip was silent, although the source app may not allow capture | Blocked and silent sources both record as exact zeros, so the text must cover both causes | Closed by Cap 3 |
