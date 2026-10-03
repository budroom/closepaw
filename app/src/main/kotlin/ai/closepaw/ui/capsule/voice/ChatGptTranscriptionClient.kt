package ai.closepaw.ui.capsule.voice

import ai.closepaw.auth.CodexHeaders
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DictationException(val error: VoiceError) : IOException("ChatGPT dictation: $error")

/** Subscription dictation, matching tgbot-image's multipart protocol. No API-key fallback. */
internal class ChatGptTranscriptionClient(
    private val headerSupplier: suspend () -> CodexHeaders,
    client: OkHttpClient = OkHttpClient(),
) {
    companion object {
        const val MAX_AUDIO_BYTES = 20_000_000L
        private const val MAX_RESPONSE_BYTES = 256_000L
        private const val ENDPOINT = "https://chatgpt.com/backend-api/transcribe"
    }

    // A response must never redirect the recording or account credentials to another endpoint.
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(recording: File, languageTag: String): String {
        if (recording.length() !in 1..MAX_AUDIO_BYTES) throw DictationException(VoiceError.NoMatch)
        val headers = headerSupplier()
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.m4a", recording.asRequestBody("audio/mp4".toMediaType()))
        val language = Locale.forLanguageTag(languageTag).language
        if (language.matches(Regex("[a-z]{2}"))) form.addFormDataPart("language", language)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${headers.accessToken}")
            .header("originator", "closepaw")
            .header("Accept", "application/json")
            .apply {
                headers.chatgptAccountId?.takeIf { it.isNotBlank() }?.let {
                    header("ChatGPT-Account-Id", it)
                }
            }
            .post(form.build())
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { parseResponse(it) } }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    private fun parseResponse(response: Response): String {
        when (response.code) {
            401, 403 -> throw DictationException(VoiceError.SignInRequired)
            429 -> throw DictationException(VoiceError.UsageLimit)
        }
        if (!response.isSuccessful) throw DictationException(VoiceError.TranscriptionFailed)
        val body = response.body
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw DictationException(VoiceError.TranscriptionFailed)
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1)) throw DictationException(VoiceError.TranscriptionFailed)
        // Never log the body: errors may contain echoed credentials, and transcripts are private.
        val json = try {
            JSONObject(source.readUtf8())
        } catch (_: Exception) {
            throw DictationException(VoiceError.TranscriptionFailed)
        }
        val text = (json.opt("text") as? String)?.trim()
            ?: throw DictationException(VoiceError.TranscriptionFailed)
        if (text.isEmpty()) throw DictationException(VoiceError.NoMatch)
        if (text.length > 16_000) throw DictationException(VoiceError.TranscriptionFailed)
        return text
    }
}
