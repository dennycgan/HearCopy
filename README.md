# HearCopy

HearCopy is an Android app that turns a few seconds of audio playing on
your phone into text you can select and copy. A floating button,
hearcopyTouch, sits over other apps (for example TikTok): tap it to start,
tap it again to stop, and the clip is transcribed by OpenAI
`gpt-transcribe`.

## Status

- Version 0.1.0. V1 is in progress: capture, the floating button and the
  transcription loop work. The main screen and key setup are temporary;
  the next step rebuilds them, adds settings and keeps the button's
  position across restarts.
- There is no release APK yet; build it from source (below).
- Transcription is English only. The language is fixed in code.
- Tested on an Android 10 phone (called Device-A in the docs) with TikTok
  as the audio source. An Android 11 phone is checked before V1. Minimum
  Android 10, phones in portrait.
- You need your own OpenAI API key. Every transcription is billed to your
  OpenAI account.

## How it works

Open HearCopy, save your key, and allow what it asks for: recording
audio, showing over other apps, notifications, and Android's
screen-capture consent, which Android requires for capturing the audio
other apps play. The session then starts and hearcopyTouch appears. Tap
it to start a recording and tap again to stop. A clip shorter than 1
second is discarded without an upload; a recording stops by itself at 5
minutes. Each clip is uploaded once. The transcript appears on HearCopy's
main screen, and a notification says when it is ready or why it failed;
the notification never contains the transcript. The last transcript is
kept until the next one replaces it, also across restarts. To end the
session, remove HearCopy from Recents: this also cancels an upload in
progress, and a result that arrives later is dropped.

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
  saved. Delete key removes it and ends the session.
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

## Continuous capture during a session

While a session is active (hearcopyTouch is on screen), playback capture
runs all the time, not only after a tap. This is a deliberate trade-off:
on the test phone, every start of a playback capture muted the speaker
for about 2 seconds, so starting it on each tap would cut out what you
are listening to.

- Audio that is not part of a clip is discarded as soon as it is read;
  nothing of it is stored or sent.
- A clip holds the audio from the tap to the stop, plus up to the
  capture backlog before the tap (at most 400 ms; 0 ms measured on
  Device-A) and up to about 100 ms after the stop.
- The cost: one short mute when a session starts, heard only if
  something is playing, and the phone stays awake during a session. The
  battery cost has not been measured.
- HearCopy shows a notification for the whole session. To end it, remove
  HearCopy from Recents.

## Which apps can be captured

Android only lets HearCopy capture audio that the playing app allows.
Audio is left out when the app targets Android 9 or lower without opting
in, when it sets a policy that blocks capture, or when it plays audio as
something other than media, game or unknown. Left-out audio records as
silence, so HearCopy reports that no audio was heard; it cannot tell a
blocked app from a silent one. TikTok works; the test phone's built-in
gallery app does not. To check an app, play something and record a
short clip of at least 1 second.

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
Guard tests enforce the architecture: package layout, import direction,
a closed dependency allowlist, the manifest, where UI text and colours
may live, the repository layout and an English-only check. CI runs the
build, all tests and lint, a full-history gitleaks secret scan and the
commit check, with every action pinned by commit SHA. The agent can
build, install and read device logs but cannot see or hear the phone, so
the owner accepts every phase on a real device.

HearCopy is not affiliated with OpenAI or TikTok.

## Licence

MIT. See [LICENSE](LICENSE).
