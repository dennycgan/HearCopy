# Changelog

Each published release is listed here. The release date is on its GitHub
Release page.

## 1.0.0

The first release.

- A floating button, hearcopyTouch, captures a short clip of the audio
  another app is playing (for example TikTok) and sends it to OpenAI
  `gpt-transcribe`; the transcript can be selected and copied.
- Capture runs for the whole session and a tap only marks the clip, so
  the speaker is not muted on each tap. Clips shorter than 1 second are
  discarded; a recording stops by itself at 5 minutes.
- Each clip is uploaded at most once, with no automatic retry. A
  notification shows the result, and every failure says what to do
  next.
- The latest transcript stays on the main screen across restarts, and
  the button keeps its position.
- You bring your own OpenAI API key. It is encrypted with a
  non-exportable Android Keystore key; Test transcription checks it with
  a built-in clip.
- A guided setup on the main screen: API key, display over other apps,
  microphone permission (needed for playback capture) and audio capture
  consent.
- Android 10 or later, phones in portrait.
