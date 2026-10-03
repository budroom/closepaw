package ai.closepaw.ui.capsule.voice

/**
 * Provider-agnostic speech-recognition errors.
 *
 * Mirrors the categories the UI actually cares about (retry vs. give up vs. send to settings)
 * without leaking `android.speech.*` constants to call sites — the recorder and transcription client map errors at the boundary.
 */
enum class VoiceError {
    NoMatch,
    SpeechTimeout,
    Network,
    NetworkTimeout,
    LanguageUnavailable,
    InsufficientPermissions,
    Busy,
    ServiceDied,
    SignInRequired,
    UsageLimit,
    TranscriptionFailed,
    Unknown,
}

/**
 * Callbacks fired by a [Recognizer] during a recognition session.
 *
 * All callbacks are delivered on the main thread by the host coroutine scope.
 * The recognizer fires AT MOST one terminal callback per session — either [onFinal] OR [onError],
 * never both. [onPartial] may fire zero or more times before the terminal callback.
 */
interface RecognizerCallbacks {
    fun onProcessing() {}
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: VoiceError)
}

/**
 * A single recognition session controller.
 *
 * Instances are NOT thread-safe and MUST be created and used from the main thread (the framework
 * recognizer this typically wraps has that constraint). One instance can be reused across
 * sessions: call [start] / [stop] / [cancel] as needed, and [destroy] once when finished.
 */
interface Recognizer {
    fun start(languageTag: String, callbacks: RecognizerCallbacks)
    fun stop()
    fun cancel()
    fun destroy()
}

/**
 * Factory that checks microphone availability and creates dictation instances.
 *
 * Split from [Recognizer] so callers can probe availability without paying the cost of
 * constructing a recognizer they may not be able to use (and so unit tests can substitute a
 * fake that reports availability without touching the framework).
 */
interface RecognizerFactory {
    fun isAvailable(): Boolean
    fun create(): Recognizer?
}
