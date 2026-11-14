# HearCopy Phase History

Archive of the phases of each closed Cap or roadmap step. Append only.

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

## Cap 2: Transcription loop (2026-11-12)

- [x] 2.1 Minimum clip length: TapStop stop time, discard below 1.0 s
  without upload (ADR-010) (6b38170)
- [x] 2.2 Transcription client: OkHttp request, JSON parsing, error
  mapping, no silent retry, key hygiene; JVM tests only (ADR-011,
  ADR-012) (b9517a2)
- [x] 2.3 API key: Keystore AES-GCM storage in DataStore, temporary key
  field, key as a start condition, KeyRemoved (ADR-013) (e566261)
- [x] 2.4 Transcription loop on Device-A (first real-key phase): upload
  after save, Completed, transcript persistence and display,
  notification, Processing haptic, KeyUnreadable teardown and alert
  (ADR-014) (13d6de4)
- [x] 2.5 Lifecycle with a transcription in flight: SessionLost,
  deferred stop, Recents cancel, consent after the deferred stop
  (ADR-015) (8ed5559)

## Public repo (2026-11-12)

- [x] Full-history privacy audit: keys, personal email, local absolute
  paths, real device model names, private notes never committed; no
  finding up to e779446
- [x] LICENSE: MIT (ffa31cc)
- [x] CLAUDE.md publication: reviewed for public reading, tracked and
  added to the RepoLayoutTest root allowlist; .claude/ stays private
  (ADR-016) (683ee0e)
- [x] README: status, build, security (key storage, where the key and
  audio are sent, key guidance in three tiers), continuous capture
  disclosure, capture limits, "How this was built" (9457195, 0011dd3,
  fb4e854)
- [x] Audit of the commits made after the full-history audit: no
  finding up to fb4e854
- [x] Visibility switch (owner)
- [x] GitHub secret scanning, push protection and private vulnerability
  reporting (owner)
- [x] Outside check while logged out (owner)

## Cap 3: Main UI, settings and lifecycle completion (2026-11-13)

- [x] 3.1 hearcopyTouch position across restarts: stored on drag end
  in its own DataStore file, restored at process start through state/
  and CaptureCommands (ADR-017) (1381c97)
- [x] 3.2 Main screen and settings page (real key): header, status card
  that walks the setup steps, transcript with Copy all, settings page
  with key save, replace and delete; notification taps return to the
  main screen; lifecycle-runtime-compose; D-002 re-check (ADR-018)
  (13765c7)
- [x] 3.3 Key setup and failure guidance (real key): clipboard cleared
  after a key save, Test transcription with a built-in clip, failure
  and alert texts that say what to do (D-006), InitFailed removed
  (ADR-019) (e681af4)
