# Voice Input

> ChatGPT subscription dictation in the main app and Smart Capsule.
> Last updated: 2026-10-03

Tap the microphone, speak, then tap Stop. ClosePaw records mono AAC in a temporary
private M4A file and transcribes it through ChatGPT. The resulting text is added to
the draft for review; it is not automatically submitted to the agent.

## Subscription transport

`ChatGptTranscriptionClient` follows the dictation protocol used by `tgbot-image`:
`POST https://chatgpt.com/backend-api/transcribe`, multipart `file`, optional
two-letter `language`, bearer access token, and optional `ChatGPT-Account-Id`.
`AuthStore.codexHeaders(OPENAI_CODEX)` supplies fresh credentials for each upload.
This is an internal ChatGPT dictation interface, so upstream changes may require
updates. No Platform API key or Android speech-recognition service is used.

The destination is fixed, redirects and automatic HTTP retries are disabled, and
responses are bounded to 256 KB. Recordings are limited to five minutes / 20 MB;
transcripts to 16,000 characters. Credentials, audio, and transcript bodies are not
logged by the voice client. Authentication and limit failures show a message and
leave the draft editable. There is no usage-reset action or alternate-provider fallback.

## Components and lifecycle

- `Recognizer.kt`: provider-independent callbacks, microphone availability, and errors.
- `ChatGptRecognizer.kt`: microphone recording and transcription, owned by the host
  composition's coroutine scope. Recording setup, file access, and microphone release
  run off the main thread. Audio files are deleted on success, failure, and cancellation.
- `VoiceInputController.kt`: `Idle → Listening → Stopping → Idle`; `Stopping` displays
  “Transcribing…”. The five-minute cutoff also enters this state. Generation checks
  discard callbacks from cancelled inputs. Transient cloud failures keep the mic available.
- `VoicePermissionGate.kt`: requests Android microphone permission. The main app uses
  its activity; the overlay routes permission requests through MainActivity.
- `CapsuleInputBar.kt`: disables Send while recording or transcribing. Editing the draft
  in either state cancels voice input, preserving the user's edit.

Leaving the host lifecycle or disposing the input cancels capture and any in-flight
upload. Speech is inserted only in the same draft that started the recording.
`languageTag` defaults to the device locale; unsupported/auto language codes omit the
language field so ChatGPT can detect the language.

## Verification

JVM tests cover multipart requests and credential refresh, bounded responses, sign-in
and limit errors without retries, microphone/upload cancellation, private-file cleanup,
and controller state transitions. Compose tests cover permission routing, mic controls,
draft editing, and submission gating. Live transcription requires ChatGPT sign-in on
the device and microphone access.
