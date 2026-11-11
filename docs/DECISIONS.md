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

## ADR-007: Capture session lifecycle
> A session ends by Recents removal without an alert, or by lost authorisation with the button hidden at once and an alert; reopening reuses the live session or asks for consent again.
- Status: Accepted (2026-11-10)
- Anchor: cc0a16a
### Context
- The product spec's lifecycle rules: removing HearCopy from Recents ends
  the session; reopening reuses the session or asks again; lost
  authorisation hides the button (not greyed out), alerts, and never
  shows a false Ready or Recording.
- The spec lists a reopen case "service running, projection invalid".
- Revoking the overlay permission is an app-op change: the process lives
  and the window manager only hides the button, so capture would go on
  with no way to stop a recording.
- On Device-A, removeView deferred the window removal until teardown
  returned; AudioRecord.stop() after an external projection stop took
  1054 ms, so the button stayed visible for about 1.1 s.
### Decision
- Teardown reasons: UserEnded (Recents, via onTaskRemoved) and Internal
  post no alert; ProjectionLost, PermissionLost (RECORD_AUDIO at a start,
  the overlay app-op, an overlay that cannot be shown) and CaptureLost
  (the ADR-005 capture loop gives up) hide the button and post the alert.
- The service stops only once no transcription is in flight; Cap 1 has
  none and stops at once. Cap 2 designs the in-flight case.
- Overlay: an AppOps watcher posts to the main thread and re-checks
  Settings.canDrawOverlays; teardown only when it is false.
- Teardown runs on the main thread once per service instance; later
  calls return early. The button is removed first, with
  removeViewImmediate.
- The alert opens the single MainActivity through the launcher intent
  with an extra; it re-enables auto-advance. A new session cancels a
  stale alert.
- Back moves the task to the background on API 29-30; API 31+ does this
  by default.
### Consequences
- Reopen has two cases: reuse, or start fresh and ask for consent. The
  case "service running, projection invalid" cannot persist.
- Process death cannot alert; the next open recovers from the true state.
- Measured on Device-A: onStop -> button hidden 63 ms (was about 1.1 s
  with removeView). AudioRecord.stop() still blocks the main thread for
  about 1 s after an external stop, with no visible effect.
- On Device-A, Recents removal runs onTaskRemoved and the full teardown,
  and the system reclaims the process about 1 s later; startup cleanup
  covers a kill that comes first.
- The alert posts even with HearCopy in front.
- Granting consent again stops any other app's projection (one
  projection at a time).
- Observed behaviour of another app on Device-A: when HearCopy takes the
  projection back while a third-party screen recorder is recording, the
  recorder's end-of-recording handling stops HearCopy's new projection
  79-128 ms after the session starts; this is handled as ProjectionLost,
  and the next consent keeps a stable session.

## ADR-008: Capture source built through a factory type
> CaptureService gets its audio source from a factory that AppContainer holds, so the source is constructed only in AppContainer and the projection type stays in the two allowed files.
- Status: Accepted (2026-11-08)
- Anchor: 5ce3820
### Context
- Rule 7: concrete implementations are constructed only in AppContainer.
- Rule 2: MediaProjection appears only in CaptureService and
  PlaybackCaptureSource.
- The source needs the session's projection, which exists only inside
  the service after consent.
### Decision
- capture/PlaybackCaptureSource.kt declares
  typealias AudioSourceFactory = (MediaProjection) -> AudioSource.
- AppContainer holds val audioSourceFactory: AudioSourceFactory =
  { PlaybackCaptureSource(it) }. At the anchor the service called it once
  per recording attempt; since ADR-005 it calls it once per session and
  once per capture restart.
- Rejected: constructing the source in CaptureService (breaks Rule 7);
  editing a guard to allow it.
### Consequences
- Both rules hold without a guard change.
- Tests substitute a fake source through the same function type.
- The factory takes a MediaProjection, so the V2 microphone source that
  the product spec reserves, which needs no projection, will need a
  different factory shape.

## ADR-009: Debug-only clip retention
> Debug builds keep the last three valid clips for listening checks; release builds keep nothing.
- Status: Accepted (2026-11-08)
- Anchor: 5ce3820
### Context
- Capture quality on the device can only be judged by listening to the
  clips; Cap 1 has no transcription.
- The product keeps no audio after processing.
### Decision
- Gated at runtime by ApplicationInfo.FLAG_DEBUGGABLE; no build-file
  change.
- The last three valid clips are kept as no_backup/debug/capture-1.wav
  .. capture-3.wav, rotating; failed, cancelled and silent clips are not
  kept.
- Startup cleanup empties only no_backup/capture/, never debug/.
### Consequences
- Debuggable installs hold up to three clips of captured audio in
  app-private, no-backup storage.
- Release builds behave as the product spec says.

## ADR-010: Clips shorter than one second are discarded
> A recording stopped less than 1.0 s after its first audio, on the overlay timer's clock, is discarded without an upload and the button returns to Ready.
- Status: Accepted (2026-11-10)
- Anchor: 6b38170
### Context
- In Cap 1 phase 1.5, deliberate rapid tapping produced clips of about
  0.1-0.3 s, and every one was saved. From Cap 2 on, each saved clip is
  uploaded and billed; the billing granularity is not documented.
- The user sees the overlay timer. A stop while it still shows 00:00 is
  an accidental double tap, not a request.
### Decision
- MIN_CLIP_MS = 1000 in state/. TapStop carries the stop time, stamped
  with elapsedRealtime on the main thread at the tap; reduce compares it
  with Recording.startedAtMs (first audio). Below the minimum:
  Recording -> Ready, no Failed state, attempt unchanged. Otherwise
  Processing.
- The session completes with CaptureResult.TooShort and the reader
  deletes the clip when it detaches it. No upload, no notification, no
  feedback; the previous transcript is untouched; debug retention does
  not keep it.
- The 300 s limit is not subject to the minimum.
- Rejected: the sample count as the measure (it lags the clock by a
  partial read and can drop a clip that already showed 00:01); the
  reader's detach as the stop time (up to about 100 ms after the tap); a
  Failed state or a notification for a discarded clip.
### Consequences
- The timer ticks on whole seconds, so it can show 00:00 for a few ms
  after 1000 ms; such a clip is kept. Accepted.
- An intended clip shorter than 1 s is lost; the constant can be tuned
  on Device-A.
- Device-A at the anchor: discards at 108, 238 and 137 ms; kept clips of
  3.9 s and 6.5 s by WAV length.

## ADR-011: org.json:json for JVM tests only
> JVM unit tests use the org.json:json artifact because Android's built-in org.json is a stub on the JVM; the app keeps the platform parser.
- Status: Accepted (2026-11-11)
- Anchor: b9517a2
### Context
- transcription/ parses the response with org.json, which Android ships
  in the platform; the V1 allowlist (ADR-001) has no JSON library.
- In local unit tests the android.jar org.json classes are stubs, so the
  parser cannot run on the JVM.
- The implementations differ: for a JSON null, Android's optString and
  getString return the string "null", while org.json:json returns the
  fallback or throws; org.json:json optString also turns non-string
  values into strings.
### Decision
- org.json:json 20260814 (Public Domain) is added to the catalog and the
  DependencyAllowlistTest allowlist, declared with testImplementation
  only.
- The client reads text, error.type and error.code only when
  isNull(name) is false and opt(name) is a String, so both
  implementations agree on null, missing and non-string values; tests
  cover all three.
- Rejected: a hand-written JSON reader in transcription/ (about 120
  lines of own code to test and maintain); unitTests.isReturnDefaultValues
  (returns defaults, does not parse).
### Consequences
- Tests run against a different org.json than the device; the platform
  parser first runs on Device-A in phase 2.4.
- The allowlist checks coordinates, not configurations; test-only is
  held by review, as for the other test libraries. At the anchor,
  org.json:json and mockwebserver3 are absent from
  releaseRuntimeClasspath.

## ADR-012: The transcription client sends each clip at most once
> The OkHttp client never re-sends a clip on its own and bounds every call with a 120 s call timeout instead of read and write timeouts.
- Status: Accepted (2026-11-11)
- Anchor: b9517a2
### Context
- Every upload is billed. OkHttp 5.5.1 re-sends a request on 503 with
  Retry-After: 0 (not gated by retryOnConnectionFailure), on 408, on 421
  over a coalesced connection, on redirects, and after some connection
  failures.
- OkHttp defaults: connect, read and write 10 s, no call timeout. A long
  clip can take more than 10 s between bytes while the server
  transcribes.
### Decision
- The multipart body is wrapped in a RequestBody with isOneShot() =
  true; retryOnConnectionFailure(false); followRedirects(false).
- connect 15 s, read 0, write 0, call 120 s; the 120 s value is the
  default inside transcription/.
- enqueue, with call.cancel() on coroutine cancellation; cancellation is
  rethrown, never mapped to an error. A failure on a cancelled call
  whose coroutine is still active is the call timeout and maps to
  Timeout, in onFailure and in the body read alike. A connect timeout
  is Network.
- The key is checked against the save rule (non-empty, every character
  0x21-0x7E, no trim) before any request; a failure is InvalidKey with
  nothing sent. OkHttp's header IllegalArgumentException is still
  caught and mapped to InvalidKey; it is unreachable while the
  pre-check exists.
- Errors carry no exception message, response body or key.
- Rejected: automatic retry with backoff (can double-bill an upload the
  server already processed).
### Consequences
- Transient failures (a stale or shut-down connection, a single 503)
  surface as errors; the user taps again.
- A 3xx is Rejected, not followed.
- Cancelling after the request body was sent does not stop the server;
  that upload may still be billed.
- okhttp-android merges INTERNET and an androidx.startup initializer
  into the manifest from phase 2.2, and raises androidx.startup to 1.2.0
  and androidx.annotation to 1.10.0; phase 2.4 declares INTERNET in the
  app manifest.
- The 120 s bound is checked against a 3-5 min clip on Device-A in 2.4.
