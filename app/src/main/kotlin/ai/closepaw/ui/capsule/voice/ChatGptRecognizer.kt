package ai.closepaw.ui.capsule.voice

import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.AuthError
import ai.closepaw.llm.LLMProvider
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** Both app and overlay use the same ChatGPT sign-in and composition-owned coroutine scope. */
class ChatGptRecognizerFactory(context: Context, private val scope: CoroutineScope) : RecognizerFactory {
    private val appContext = context.applicationContext
    private val client = ChatGptTranscriptionClient(headerSupplier = {
        withContext(Dispatchers.IO) {
            AuthStoreHolder.get(appContext).codexHeaders(LLMProvider.OPENAI_CODEX)
        }
    })

    override fun isAvailable(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    override fun create(): Recognizer = ChatGptRecognizer(
        scope = scope,
        recordingFile = { File.createTempFile("dictation-", ".m4a", appContext.cacheDir) },
        record = { file, stop -> recordAudio(appContext, file, stop) },
        transcribe = client::transcribe,
    )
}

/** One coroutine owns the microphone, temporary file, upload, and all cleanup. */
internal class ChatGptRecognizer(
    private val scope: CoroutineScope,
    private val recordingFile: () -> File,
    private val record: suspend (File, Deferred<Unit>) -> Unit,
    private val transcribe: suspend (File, String) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Recognizer {
    private var job: Job? = null
    private var stopSignal = CompletableDeferred<Unit>()

    override fun start(languageTag: String, callbacks: RecognizerCallbacks) {
        cancel()
        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        job = scope.launch {
            var recording: File? = null
            try {
                withContext(ioDispatcher) { recording = recordingFile() }
                record(recording!!, stop)
                coroutineContext.ensureActive()
                callbacks.onProcessing()
                val text = transcribe(recording, languageTag)
                coroutineContext.ensureActive()
                callbacks.onFinal(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = when (e) {
                    is DictationException -> e.error
                    is AuthError -> VoiceError.SignInRequired
                    is SecurityException -> VoiceError.InsufficientPermissions
                    is SocketTimeoutException -> VoiceError.NetworkTimeout
                    is IOException -> VoiceError.Network
                    else -> VoiceError.TranscriptionFailed
                }
                callbacks.onError(error)
            } finally {
                withContext(NonCancellable + ioDispatcher) { recording?.delete() }
            }
        }
    }

    override fun stop() { stopSignal.complete(Unit) }
    override fun cancel() { job?.cancel(); job = null }
    override fun destroy() = cancel()
}

private suspend fun recordAudio(context: Context, file: File, stop: Deferred<Unit>) {
    var recorder: MediaRecorder? = null
    try {
        withContext(Dispatchers.IO) {
            MediaRecorder(context).apply {
                recorder = this
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(24_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }
        // Bounded even if the user leaves the mic running. AAC at 64 kbps stays below 20 MB.
        withTimeoutOrNull(300_000L) { stop.await() }
        withContext(Dispatchers.IO) {
            try {
                recorder!!.stop()
            } catch (_: RuntimeException) {
                // Android rejects stop when too little audio was captured (e.g. a double tap).
                throw DictationException(VoiceError.NoMatch)
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            recorder?.release()
        }
    }
}
