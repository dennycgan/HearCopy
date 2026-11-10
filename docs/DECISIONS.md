# HearCopy Decisions

Architecture Decision Records. Append only; old reasoning is never
edited, only the Status line changes.

## ADR-001: Guard tests and DECISIONS are the architecture of record
> The kickoff's architecture and stack are turned into guard tests once, in Cap 0, and from then on only the guards and this log define the architecture.
- Status: Accepted (2026-11-08)
- Anchor: bd394d0
### Context
The kickoff defines packages, import rules, a closed tech stack and
manifest/security constraints, but it is a private, editable product spec,
not something the build can check. It also says the Konsist guards may come
after the first usable APK if they would delay delivery.
### Decision
- The kickoff's package layout, import rules, dependency stack, manifest
  constraints, copy/theme rules and language rule are translated once into
  seven JVM tests under app/src/test/.../architecture/ (RepoLayout,
  PackageLayout, DependencyDirection, DependencyAllowlist, Manifest,
  CopyAndTheme, Language).
- From that commit on, the guard tests plus DECISIONS.md are the
  architecture of record. The kickoff is product reference only; code is
  never moved to "match the kickoff".
- The guards, Konsist included, land in Cap 0, before any feature code,
  instead of after the first usable APK as the kickoff allowed.
- The allowlists cover the whole approved V1 set now: root files
  (README.md, LICENSE, CHANGELOG.md), libraries (kotlinx-coroutines-android,
  kotlinx-coroutines-test, okhttp, mockwebserver3, datastore-preferences,
  lifecycle-runtime-compose) and manifest permissions (INTERNET,
  RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS). The catalog and
  manifest still hold only what is in use.
- Changing a guard or an allowlist is an architecture change: owner
  approval and a new ADR in the same scope.
### Consequences
- Every feature Cap starts with the boundaries already enforced locally
  and in CI; violations fail `./gradlew test`.
- Later Caps add pre-approved libraries and permissions without an ADR;
  anything outside the V1 set needs one.
- Cap 0 spends some time before the first useful APK; accepted, because the
  guards are cheap while the codebase is empty and costly to retrofit.
- Guards against empty packages pass vacuously in Cap 0; each was shown to
  fail on a deliberate local violation before being committed.
- Guards scan tracked plus untracked-not-ignored files, so a stray file
  fails locally before it is staged.

## ADR-002: CI with pinned tools and one shared commit-message check
> Every push runs build, tests, lint, a full-history secret scan and a full-history commit-message check, using only SHA-pinned actions, a checksum-verified gitleaks binary and one shared rules script.
- Status: Accepted (2026-11-08)
- Anchor: db901ac
### Context
The commit-message rules lived in an untracked local hook and in a
separate post-commit check, and the two had already drifted (CJK
punctuation, whitespace before trailers, committer identity). The
official gitleaks action needs a licence for some uses, and setup-gradle
defaults to a commercial caching component. Tags of third-party actions
are mutable.
### Decision
- .github/workflows/ci.yml runs on push to any branch: commit check,
  gitleaks, then ./gradlew assembleDebug test lint on Temurin JDK 25.
- .github/scripts/check-commit-msg.pl is the only copy of the
  commit-message and identity rules. The local commit-msg hook is a thin
  wrapper around it and fails closed if it is missing; the post-commit
  check and CI call it with --commit.
- CI checks every commit in git rev-list HEAD on each run, not only the
  pushed range.
- gitleaks is a release binary with version and SHA-256 hard-coded in
  ci.yml and verified with sha256sum -c; it scans the full git history.
  The licensed gitleaks action is rejected.
- Every third-party action is pinned by full commit SHA. The workflow
  token is read-only and checkout does not persist credentials.
- setup-gradle uses cache-provider: basic (open source) and validates the
  wrapper jar.
- The runner is pinned to the concrete label ubuntu-24.04 instead of
  ubuntu-latest; moving to a newer OS image is a deliberate change.
### Consequences
- Actions and gitleaks never upgrade on their own: upgrading means
  updating a SHA or checksum by hand. The runner label fixes only the OS
  version; GitHub still updates the image's preinstalled software weekly,
  and retires old labels, which forces a move to a newer image.
- Any commit authored or committed by someone else, including commits
  made by GitHub (web edits, merge button, bots), fails CI. Changes reach
  main only as local commits pushed by the owner.
- CI runs the rules script from the commit under test, so a commit that
  weakens the script is checked by the weakened script. CI cannot guard
  its own rules; review of script changes is the guard.
- The gitleaks checksum comes from the same unsigned release; it protects
  against later replacement, not against a compromised release.
- Committing from a checkout older than this commit (no script) is
  rejected by the hook.

## ADR-003: Portrait-only V1
> V1 locks the main screen to portrait and promises phones in portrait only; the floating button makes no landscape promise but always stays on screen.
- Status: Accepted (2026-11-08)
- Anchor: 5ce3820
### Context
HearCopy is used alongside TikTok-style portrait video. Neither the
temporary main screen nor the Cap 3 main screen needs a landscape layout.
Supporting rotation would add layout and lifecycle cases to design and
test on every Cap without a product need.
### Decision
- MainActivity declares android:screenOrientation="portrait". The lock
  does not depend on the device's auto-rotate setting.
- hearcopyTouch draws over other apps, which may run in landscape. V1
  makes no landscape promise there, but the button is clamped to the
  screen on configuration changes so it never sits off-screen during a
  recording.
- Saved-state handling in MainActivity stays: other configuration
  changes (dark mode, font scale, locale) and process death still
  recreate the Activity.
### Consequences
- No landscape testing in V1; rotation checks are removed from the
  device checklists.
- On Android 16+ the orientation lock is ignored on large screens
  (smallest width >= 600dp) for apps targeting API 36+; with targetSdk 37
  there is no opt-out. V1 promises phones only.
- Lint reports LockedOrientationActivity and DiscouragedApi on the
  manifest line; accepted as a consequence of this decision.

## ADR-004: Capture at 48 kHz and decimate to 16 kHz
> Playback audio is captured at 48 kHz and converted to 16 kHz inside the capture source with a low-pass FIR filter, because requesting 16 kHz from the platform yields zero-filled audio on Device-A.
- Status: Accepted (2026-11-08)
- Anchor: 5ce3820
### Context
The kickoff asks for PCM 16-bit, 16 kHz, mono and lets the system
resample. On Device-A (Android 10) a playback-capture record built at
16 kHz initialises normally, but about 28% of the samples arrive as exact
zeros (about 17.6 ms every 64 ms), heard as crackle throughout. A
diagnostic build showed: the output mixer runs at 48 kHz; at 48 kHz the
capture has no zero fill after start-up and the clips are clean by
listening; at 44.1 kHz about 1.4% of samples are still zero-filled. The
zero fill is consistent with the remote submix path supporting only its
native rates.
### Decision
- PlaybackCaptureSource requests 48000 Hz mono PCM 16-bit and requires
  the record to report 48000; anything else is InitFailed. It never
  falls back to another rate on its own.
- It decimates by 3 to 16 kHz with a 175-tap linear-phase FIR (Kaiser
  window, passband to 7 kHz, at least 60 dB attenuation from 8 kHz) in a
  pure-Kotlin Decimator, before samples leave the source. The filter
  applies 3 dB of headroom (DC gain 10^(-3/20)) so that its overshoot on
  loud audio is not clamped; the 16-bit clamp remains only as a safety net.
- The AudioSource contract stays PCM 16-bit, 16 kHz, mono, so
  CaptureSession, WavWriter, SilenceDetector, the 300 s sample cap and
  the WAV header are unchanged.
- The record buffer is at least 400 ms at 48 kHz (38400 bytes).
### Consequences
- About 2.8 million multiply-adds per second on the reader thread
  during a recording; no new dependency.
- The 16 kHz escape hatch is closed. The 24 kHz accuracy fallback, if
  ever needed, becomes decimation by 2 from the same capture.
- Devices whose capture path does not run at 48 kHz fail with InitFailed
  until a further decision; Device-B is checked before V1.
- Output is 3 dB quieter than the source, so the -50 dBFS silence
  threshold corresponds to about -47 dBFS of the source.
- Samples already clipped in the platform mix are not recoverable; the
  headroom only prevents clipping added by the filter.
- The start-of-recording speaker dropout is not addressed here; it is
  caused by startRecording() at every rate and is diagnosed separately.

## ADR-005: Continuous playback capture during a session
> While a capture session is active, the playback-capture record runs continuously and a tap only decides which audio is kept, because every record start mutes the speaker for about 2 s on Device-A.
- Status: Accepted (2026-11-08)
- Anchor: 79fcc26
### Context
- The product spec asks for capture only after a tap and no always-on
  capture.
- On Device-A (Android 10), every startRecording() of a playback-capture
  record makes the audio policy connect the remote-submix device and move
  media tracks to it; during the move it mutes media on the speaker output
  and restores it 2 s later. The user hears TikTok drop out for about 2 s on
  every tap while the video keeps playing.
- Diagnostics: build() is not the cause; one record kept and restarted per
  tap still mutes on every start; a third-party screen recorder using the
  same API (44.1 kHz, same mix type, route flags and usages) mutes the same
  way; one record started once per session and read throughout showed no
  further mute across video switches, pause/resume and a screen lock.
### Decision
- The record is built and started once per session, right after the
  session starts (HearCopy in front), and stopped and released at teardown.
- A tap attaches a per-attempt clip at a chunk boundary; stop detaches it.
  The recording style starts with the first written chunk.
- No pre-roll: while no clip is attached, each read is discarded at once;
  no ring buffer, nothing on disk. Idle work is read and discard only: the
  source skips at its native rate, no decimation, no silence detection.
- Decimation stays inside the source (ADR-004), reset when a clip starts.
- A read error restarts the record within a budget (1 per 60 s, 3 per
  session); a stuck read, a used-up budget or a failed start ends the
  session.
- Rejected: a new record per tap; pre-creating the record on Ready (build
  is not the cause); one record restarted per tap; a start/stop warm-up at
  session start (the next start mutes again); turning red only after the
  mute ends (the mute is still heard).
### Consequences
- Deviates from the product spec: capture runs during a session; only audio
  after a tap is kept. The README discloses this before V1.
- One mute per session start (unheard if nothing plays) and one per restart
  after an error; audible if media plays at that moment.
- An active session keeps the device awake (audio wakelock), also with the
  screen off; not measured, accepted by the owner.
- A read error restarts the record within a budget (1 per 60 s, 3 per
  session); a stuck read or a used-up budget ends the session.
- Capture start failures now end the session instead of failing a tap with
  InitFailed; FailureReason.InitFailed stays in state/ until the Cap 3
  failure copy is designed.
- The OEM privacy notice does not show on taps. It may show once per
  session for a few seconds when HearCopy leaves the front (seen in one of
  two Device-A runs, about 5 s).
- Pre-tap audio in a clip is bounded by the record backlog at the first clip
  read (at most the 400 ms buffer); measured on Device-A: 0 ms (record
  backlog at most 15 ms).
- A clip may run up to one chunk (about 100 ms) past the stop tap.
- The escape hatch "pre-create AudioRecord on Ready" is obsolete.

## ADR-006: hearcopyTouch keeps its place on the glass
> The floating button's position is stored in panel coordinates and mapped through the display rotation, so it stays at the same point on the glass whatever the screen content does.
- Status: Accepted (2026-11-10)
- Anchor: 89a024a
### Context
- hearcopyTouch is dragged by the user and kept in memory for the session
  (persistence is Cap 3). ADR-003 requires it to be clamped to the screen
  on configuration changes, because other apps may run in landscape.
- A first version kept one LayoutParams position (inset from the right
  edge, offset from the top) for every orientation. On Device-A most
  portrait positions collapsed onto the landscape bottom edge (three
  positions all at the same y), which the owner rejected: the position
  must be absolute, whatever the screen content does.
- The overlay sits below the status and navigation bars, and how
  LayoutParams map to the screen was not verified in advance.
### Decision
- The stored position is the button's top-left corner in panel
  (natural-orientation) coordinates, mapped through the display rotation
  (0/90/180/270) to the screen. The button stays at the same point on the
  glass; a drag in any orientation updates that one panel position.
- Split rule: a drag end stores the button's final resting position,
  after clamp and overflow correction; that write is the user's choice. A
  clamp after a configuration change moves only the applied position and
  never writes back, so the position returns when the rotation does.
- Clamp: on drag end, and after a configuration change once a requested
  layout pass has run, clamp in LayoutParams space to the visible frame;
  after the next layout, measure the window with getLocationOnScreen and
  correct any real overflow once.
- Rejected: one LayoutParams position for all orientations (the first
  version); a position proportional to the screen (it moves on the glass
  when the content rotates).
### Consequences
- Landscape is still not committed in V1 (ADR-003).
- This amends ADR-003's consequence "rotation checks are removed from the
  device checklists" to exactly one blocking device check: the overlay
  clamp and position, in both landscape directions (Cap 1 phase 1.5
  check 8). ADR-003 Status is unchanged.
- Where the panel point falls under a cutout or a system bar in the
  current rotation, the button is pushed inside the visible frame by up
  to about the bar height and returns when the rotation does.
- A system bar shown or hidden without a configuration change does not
  trigger a clamp; not observed as a problem on Device-A.
- Panel size comes from Display.getRealSize, deprecated from API 31 but
  still used for all levels; revisit if it misreports on Device-B.
