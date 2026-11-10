# HearCopy Phase History

Archive of each closed Cap's phases. Append only.

## Cap 0: Foundation (2026-11-08)

- [x] 0.1 Build skeleton: Gradle wrapper, version catalog, single :app
  module, minimal app that builds and installs (f900cc1, 9381edc)
- [x] 0.2 Architecture guard tests (RepoLayout, PackageLayout,
  DependencyDirection, DependencyAllowlist, Manifest, CopyAndTheme,
  Language); ADR-001 (bd394d0)
- [x] 0.3 CI workflow: build, unit and architecture tests, lint,
  gitleaks, commit-message check through one shared rules script;
  ADR-002 (db901ac)

## Cap 1: Capture session + hearcopyTouch (2026-11-10)

- [x] 1.1 State model: CaptureState, pure reduce, tapAction,
  CaptureStateStore, JVM tests; coroutines libraries (6b0ff0f)
- [x] 1.2 Capture pipeline: AudioSource, CaptureSession, WavWriter,
  SilenceDetector, 300 s dual cap, 2 s Preparing timeout; JVM tests
  (4a0011a)
- [x] 1.3 First real capture on Device-A: 48 kHz capture decimated to
  16 kHz (ADR-004), CaptureService, PlaybackCaptureSource,
  CaptureCommands, AppContainer wiring, notification channels, temporary
  main screen, manifest permissions, minimal hearcopyTouch, temp files and
  startup cleanup, debug-only WAV retention, diagnostics; plus
  CaptureSession hardening H1 (stale-session stop guard) and H2 (reader
  outcome guard) (5ce3820)
- [x] 1.4 Capture start without speaker dropout: continuous capture
  during a session (ADR-005), CaptureLoop, per-attempt clip at chunk
  boundaries, source skip, restart budget; JVM tests (79fcc26, 70c13a7)
- [x] 1.5 hearcopyTouch complete: drag with touch slop, Recording timer,
  Processing ring and haptic, Preparing taps ignored, translucency by
  status, in-memory position in panel coordinates with clamp (ADR-006)
  (89a024a)
- [x] 1.6 Lifecycle rules: back to background, Recents teardown, reopen
  reuse or re-request, authorisation lost (hide, alert, stop) including
  capture loss and the overlay app-op, immediate overlay removal, single
  main screen instance, no duplicate button (ADR-007) (cc0a16a)
