# HearCopy

*Catch what you just heard.*

HearCopy is an Android app that turns a short stretch of audio playing on
your phone into text you can select and copy.

## Why

Something in a video or podcast goes by and you want it as text: a line
you did not quite catch, slang or a term to look up, a name, title or
number to search, or a quote worth keeping. Often you simply want to
look up what you heard: a product, a place, a song, a claim to check.
Subtitles, where there are any, usually cannot be selected, so the usual
workarounds are a screenshot and OCR, pausing to type it out, or
replaying until you can make out the words.

HearCopy turns that into a few taps. What you do with the text is up to
you: search it, translate it, paste it into a dictionary or an AI chat,
or keep it.

It is not live captioning or translation. It transcribes only the clip
you mark, keeps only the latest transcript, and leaves the rest to
whatever app you paste into.

## Tech stack

| Layer | Choice |
|---|---|
| Language and UI | Kotlin; Jetpack Compose main screen and settings page; plain Android View for the floating button |
| Audio capture | MediaProjection, AudioPlaybackCapture and AudioRecord in a foreground service |
| Audio format | Captured at 48 kHz, decimated to 16 kHz mono WAV ([ADR-004](docs/DECISIONS.md#adr-004-capture-at-48-khz-and-decimate-to-16-khz)) |
| Transcription | OpenAI `gpt-transcribe` over OkHttp |
| State | Kotlin coroutines and StateFlow; one store with a pure reducer |
| Local data and secrets | Jetpack DataStore; API key encrypted with an Android Keystore AES-256-GCM key |
| Wiring | Hand-written `AppContainer`, no DI framework |
| Verification | JUnit unit tests, OkHttp MockWebServer, Konsist architecture tests, Android lint; GitHub Actions CI with gitleaks |

Versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Architecture

Paths are relative to the package root `com.dennycgan.hearcopy`.

| Layer | Path | Role |
|---|---|---|
| State | `state/` | `CaptureStateStore`: one StateFlow, changed only by the pure `reduce(state, event)` |
| Capture | `capture/` | `CaptureService` runs the session; `PlaybackCaptureSource` records; `TranscriptionRun` drives each upload; `CaptureCommands` is the way in |
| Overlay | `overlay/` | `HearCopyTouch`: the floating button, a plain View |
| Transcription | `transcription/` | `TranscriptionClient`: one upload per clip to `gpt-transcribe` |
| Data | `data/` | `SettingsRepository` (encrypted API key), `TranscriptRepository` (latest transcript), `TouchPositionRepository` (button position) |
| Notifications | `notify/` | `CaptureNotifications`: session, alert and transcription notifications |
| UI | `ui/` | `MainActivity`, `MainScreen` and `SettingsScreen`: the main screen (status, latest transcript) and the settings page (API key) |
| Wiring | `AppContainer.kt` | Builds the app-wide objects and wires them; created by `HearCopyApplication` |

`DependencyDirectionTest` enforces the import direction. `state/` is pure
Kotlin, with no Android imports. `ui/` and `overlay/` read state from
`state/` and reach `capture/` only through `CaptureCommands`, never
`transcription/`. `transcription/` does not import `capture/`, `overlay/`
or `ui/`. Only `data/` touches the Keystore and DataStore. The state
store, capture source, transcription client and settings repository are
built only in `AppContainer`. The reasons are in
[docs/DECISIONS.md](docs/DECISIONS.md).

## Engineering highlights

- Each clip is sent at most once, with no automatic retry
  ([ADR-012](docs/DECISIONS.md#adr-012-the-transcription-client-sends-each-clip-at-most-once)).
- Capture runs for the whole session, because starting it on each tap
  muted the speaker for about 2 seconds on the test phone
  ([ADR-005](docs/DECISIONS.md#adr-005-continuous-playback-capture-during-a-session)).
- You bring your own OpenAI key; it is encrypted with a non-exportable
  Android Keystore key
  ([ADR-013](docs/DECISIONS.md#adr-013-api-key-encrypted-with-a-keystore-aes-gcm-key-in-datastore)).
- Guard tests enforce the architecture
  ([ADR-001](docs/DECISIONS.md#adr-001-guard-tests-and-decisions-are-the-architecture-of-record));
  CI runs a full-history gitleaks scan with every action pinned by commit
  SHA
  ([ADR-002](docs/DECISIONS.md#adr-002-ci-with-pinned-tools-and-one-shared-commit-message-check)).
- Built with Claude Code as the coding agent, under owner review
  ([How this was built](#how-this-was-built)).

## Using it

A floating button, hearcopyTouch, sits over other apps (for example
TikTok) while a capture session is active.

1. Tap hearcopyTouch when you hear something you want. Nothing changes
   until audio is actually being captured; then the button shows a
   square and a running timer.
2. Drag the video back a little and let that part play again. A clip
   starts at your tap, so the part you want has to play after it.
3. Tap again to stop. While the clip is transcribed, a ring turns around
   the button and it does not take new taps. When the notification says
   "Transcription complete", tap it to open HearCopy.
4. Select the words you need in the transcript, or use Copy all.

- A clip shorter than 1 second is discarded without an upload; a
  recording stops by itself at 5 minutes.
- If a clip fails, the notification and HearCopy's main screen say why.
- The latest transcript stays on the main screen until the next one
  replaces it, also across restarts; a failure does not clear it.
- Drag hearcopyTouch where you want it; it stays there, also after a
  restart.
- To end the session, remove HearCopy from Recents: this also cancels an
  upload in progress, and a result that arrives later is dropped.

## Which apps can be captured

Android only lets HearCopy capture audio that the playing app allows.
Audio is left out when the app targets Android 9 or lower without opting
in, when it sets a policy that blocks capture, or when it plays audio as
something other than media, game or unknown. Left-out audio records as
silence, so HearCopy reports that no audio was heard; it cannot tell a
blocked app from a silent one. TikTok works; the test phone's built-in
gallery app does not. To check an app, play something and record a
short clip of at least 1 second.

## Status

- Version 0.1.0. V1 is in progress: capture, the floating button, the
  transcription loop, the main screen and the settings page work. The
  next step completes key setup.
- There is no release APK yet; build it from source (below).
- Tuned for English: each request tells the model to expect English.
  OpenAI describes `gpt-transcribe` as transcribing speech in its
  original language
  ([speech-to-text guide](https://developers.openai.com/api/docs/guides/speech-to-text)),
  including multilingual audio
  ([model page](https://developers.openai.com/api/docs/models/gpt-transcribe));
  in tests, Chinese and Japanese videos came back in Chinese and
  Japanese despite the English hint. Only English is tested regularly.
- Tested on an Android 10 phone (called Device-A in the docs) with TikTok
  as the audio source. An Android 11 phone is checked before V1. Minimum
  Android 10, phones in portrait.
- You need your own OpenAI API key. Every transcription is billed to your
  OpenAI account.

## Setup

Open HearCopy. The status card on the main screen names the next step;
HearCopy opens each system step by itself, and if you refuse one, a tap
on the card opens it again:

1. OpenAI API key: tap the card or the gear to open Settings, paste the
   key and tap Save (read Security and privacy below before you create
   one), then go back to the main screen.
2. Display over other apps: Android opens its settings; find HearCopy,
   allow it, and come back.
3. Microphone: Android requires this permission for capturing the audio
   other apps play. HearCopy captures playback only, never the
   microphone.
4. Audio capture: Android's screen-capture consent, which it requires for
   capturing the audio other apps play.

On Android 13 and later HearCopy also asks to show notifications. When
the steps are done, the capture session starts, hearcopyTouch appears,
and a "HearCopy is ready" notification stays for the whole session.
After a session ends, the next one asks for the audio capture consent
again.

## Security and privacy

HearCopy is built to keep your key and audio contained, but it does not
claim to be absolutely safe. It cannot guarantee the key's safety on a
rooted or compromised phone, or when someone else has your unlocked
phone.

### Your API key

- You type or paste the key into HearCopy. It is encrypted with an
  AES-256-GCM key held by the Android Keystore, which cannot be exported;
  only the encrypted key is stored, in HearCopy's private storage. It is
  decrypted just before each upload.
- The last 4 characters are stored unencrypted, to show which key is
  saved. Delete, in Settings, removes it and ends the session.
- There is no key in this repository or in the app. App backup is off,
  and an encrypted key copied to another phone cannot be decrypted there.
- HearCopy never writes the key to its logs or error messages.
- Only enter your key into a build made from this repository, never into
  a modified build from elsewhere.
- A pasted key stays on the clipboard: HearCopy does not clear it yet, so
  clear it yourself. Your keyboard's own clipboard history is out of
  HearCopy's reach.
- If you think the key has leaked, revoke it in the OpenAI dashboard
  straight away.

### Where your key and audio go

- HearCopy connects to one address only,
  `https://api.openai.com/v1/audio/transcriptions`, with the model
  `gpt-transcribe`. Both are fixed in code: there is no custom server
  setting and no plain HTTP. There is no analytics, advertising or
  crash-reporting SDK.
- Each request carries your key and one clip (16 kHz mono WAV). OpenAI
  handles the audio under its API data policy:
  [Data controls in the OpenAI platform](https://developers.openai.com/api/docs/guides/your-data).
- A clip is sent at most once, with no automatic retry. That does not
  cap the cost: every tap is a new upload. An upload cancelled after the
  clip was sent may still be billed.

### What stays on the phone

- A clip holds the audio from your tap to your stop, plus a little
  either side: up to the capture backlog before the tap (at most 400 ms;
  0 ms measured on Device-A) and up to about 100 ms after the stop.
- Release builds delete every clip after its upload, failure or
  cancellation; leftovers from a crash are removed at the next start.
- Only the last transcript is kept, unencrypted, in HearCopy's private
  storage, excluded from backup. It is never synced anywhere.
  Notifications never contain the transcript.
- The button's position is stored on the phone, excluded from backup.

### Continuous capture during a session

While a session is active (hearcopyTouch is on screen), playback capture
runs all the time, not only after a tap. This is a deliberate trade-off:
on the test phone, every start of a playback capture muted the speaker
for about 2 seconds, so starting it on each tap would cut out what you
are listening to.

- Audio that is not part of a clip is discarded as soon as it is read;
  nothing of it is stored or sent.
- The cost: one short mute when a session starts, heard only if
  something is playing, and the phone stays awake during a session. The
  battery cost has not been measured.
- HearCopy shows a notification for the whole session. To end it, remove
  HearCopy from Recents.

### Limiting what a leaked key can cost

Labels are as seen in the OpenAI dashboard in November 2026 and may
change.

1. Required: create an API key and paste it into HearCopy.
2. Strongly recommended, one setting: when you buy credit, switch
   **Use auto-reload** off (it is on by default). The most a leaked key
   can spend is then your current credit balance.
   Alternative: on the organisation's Limits page, edit
   **Organization spend limit**: set a **Monthly spend limit** and turn on
   **Enforce a hard limit**. Spending can slightly exceed the limit, then
   requests fail with HTTP 429. This limit covers all API usage in the
   organisation, not only HearCopy's.

<details>
<summary>3. Optional hardening</summary>

- Create a separate project for HearCopy.
- In the project settings, Limits: **Allow or block models**, then
  **Allow only selected models**, with only `gpt-transcribe`.
- Create the key with Permissions **Restricted**, then under
  **Model capabilities** allow only Audio.
- Set an **Expiration** on the key.

</details>

### Reporting

Report security issues privately through GitHub's private vulnerability
reporting.

## Build from source

You need JDK 25 and the Android SDK with platform 37 (Android Studio
provides both). The Gradle wrapper is included.

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew test
```

`./gradlew test` runs the unit and architecture tests. A debug build
keeps the last three uploaded clips in HearCopy's private storage for
listening checks; release builds keep none. There is no release signing
setup yet.

## How this was built

HearCopy was written with Claude Code as the coding agent. Its
instructions are in [CLAUDE.md](CLAUDE.md), which is public; the detailed
rules it follows are kept private. A separate Claude conversation acted
as reviewer: it read the repository docs and had read-only access to the
local working copy, and every design, key diff and device result was
reviewed there before the owner approved it. The owner approves every
commit and, separately, every push; commits carry only the owner's
identity, which a hook and CI both check.

Work is split into capabilities ("Caps"), each designed and approved
before any code is written. Decisions that bind later work are recorded
as ADRs in [docs/DECISIONS.md](docs/DECISIONS.md);
[docs/STATE.md](docs/STATE.md) is the current snapshot and
[docs/PHASE_HISTORY.md](docs/PHASE_HISTORY.md) the record of closed Caps.
Besides the import rules in [Architecture](#architecture), guard tests
check the package and repository layout, a closed dependency allowlist,
the manifest, where UI text and colours may live, and that tracked files
are English only. CI runs the build, all tests and lint, a full-history
gitleaks secret scan and the commit check, with every action pinned by
commit SHA. The agent can build, install and read device logs but cannot
see or hear the phone, so the owner accepts every phase on a real device.

HearCopy is not affiliated with OpenAI or TikTok.

## Licence

MIT. See [LICENSE](LICENSE).
