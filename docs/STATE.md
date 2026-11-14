# HearCopy State

Current snapshot only. No history. ADRs are referenced by number;
details live in DECISIONS.md.

## Capability Roadmap

- [x] Cap 0 Foundation
- [x] Cap 1 Capture session + hearcopyTouch
- [x] Cap 2 Transcription loop
- [x] Public repo
- [x] Cap 3 Main UI, settings and lifecycle completion
- [x] V1 release
- No next step is scoped yet  <- YOU ARE HERE

## Phase Roadmap

- No next step is scoped yet. Its phases are written here once the
  owner approves its design.

## Known debt

| ID | Status | Cap | Item | Reason | Picks it up |
|---|---|---|---|---|---|
| D-001 | Accepted | 0 | Lint `DataExtractionRules`: no `android:fullBackupContent` for Android 11 and lower | `allowBackup="false"` already disables backup and device transfer on Android 11 and lower | - |
| D-002 | Accepted | 0 | Lint `ObsoleteLintCustomCheck`: lifecycle library lint checks are skipped under AGP 9.5 lint | Third-party lint jar uses an API that this lint version no longer has; the skipped checks (`RepeatOnLifecycleWrongUsage`, `UnsafeLifecycleWhenUsage`) cover `repeatOnLifecycle` and `Lifecycle.whenX`, which HearCopy does not call; declaring lifecycle-runtime-compose did not clear it | - |
| D-003 | Accepted | 0 | Lint `NewerVersionAvailable` and `GradleDependency` for every catalog library they report (Konsist, kotlinx-coroutines-android, kotlinx-coroutines-test, lifecycle-runtime-compose; first seen: Konsist 0.18.1) | Dependency versions are upgraded deliberately as planned work, not on lint prompts; 0.18.0 matches Kotlin 2.4.21 exactly; lifecycle-runtime-compose is pinned at 2.9.4, the version already resolved (ADR-018) | - |
| D-004 | Accepted | 1 | Lint LockedOrientationActivity and DiscouragedApi on MainActivity screenOrientation | ADR-003 | - |
| D-005 | Accepted | 2 | The KeyUnreadable alert can be silent: the transcript notification posted just before it uses up Android's per-app sound rate limit | ADR-014; the banner still shows | - |
| D-006 | Closed | 2 | The NoAudio text says the clip was silent, although the source app may not allow capture | Blocked and silent sources both record as exact zeros, so the text must cover both causes | Closed by Cap 3 |
| D-007 | Accepted | V1 | Lint NotShrinkingResources: R8 is on and resources are not shrunk | Owner decision: no resource shrinking (ADR-021) | - |
| D-008 | Open | V1 | Not registered for Android developer verification | Owner decision for V1: ship unregistered; from Google's 2027 global rollout an unregistered app installs only with ADB or the advanced flow | Decide before the 2027 global rollout |
