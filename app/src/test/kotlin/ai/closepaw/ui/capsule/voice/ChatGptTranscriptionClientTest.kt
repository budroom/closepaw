package ai.closepaw.ui.capsule.voice

import ai.closepaw.auth.CodexHeaders
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatGptTranscriptionClientTest {
    @get:Rule val temp = TemporaryFolder()
    private fun recording() = temp.newFile().apply { writeBytes("0000ftypM4A audio".toByteArray()) }

    @Test fun `uses current subscription credentials and multipart dictation without chat access`() = runBlocking {
        val requests = mutableListOf<Pair<Request, String>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            requests += request to body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"text":"  Hello tablet  "}""".toResponseBody()).build()
        }.build()
        var headers = CodexHeaders("first-token", "account-a", null)
        val client = ChatGptTranscriptionClient({ headers }, http)
        val file = recording()
        assertThat(client.transcribe(file, "en-US")).isEqualTo("Hello tablet")
        headers = CodexHeaders("refreshed-token", "account-b", null)
        client.transcribe(file, "auto")
        assertThat(requests).hasSize(2)
        requests.forEach { (request, body) ->
            assertThat(request.url.toString()).isEqualTo("https://chatgpt.com/backend-api/transcribe")
            assertThat(request.method).isEqualTo("POST")
            assertThat(body).contains("name=\"file\"; filename=\"voice.m4a\"")
            assertThat(body).contains("Content-Type: audio/mp4")
            assertThat(body).doesNotContain("model")
        }
        assertThat(requests[0].first.header("Authorization")).isEqualTo("Bearer first-token")
        assertThat(requests[1].first.header("Authorization")).isEqualTo("Bearer refreshed-token")
        assertThat(requests[1].first.header("ChatGPT-Account-Id")).isEqualTo("account-b")
        assertThat(requests[0].second).contains("name=\"language\"\r\n\r\nen")
        assertThat(requests[1].second).doesNotContain("name=\"language\"")
    }

    @Test fun `limits and sign in failures surface without retry fallback or reset requests`() = runBlocking {
        for ((status, expected) in listOf(401 to VoiceError.SignInRequired, 403 to VoiceError.SignInRequired,
            429 to VoiceError.UsageLimit, 302 to VoiceError.TranscriptionFailed, 500 to VoiceError.TranscriptionFailed)) {
            var requests = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .header("Location", "https://example.com/other-project")
                    .message("failed").body("secret-token".toResponseBody()).build()
            }.build()
            val client = ChatGptTranscriptionClient({ CodexHeaders("secret-token", null, null) }, http)
            val error = runCatching { client.transcribe(recording(), "en") }.exceptionOrNull()
            assertThat((error as DictationException).error).isEqualTo(expected)
            assertThat(error.message).doesNotContain("secret-token")
            assertThat(requests).isEqualTo(1)
        }
    }

    @Test fun `rejects empty oversized and malformed transcripts`() = runBlocking {
        for (body in listOf("""{"text":" "}""", "{}", """{"text":42}""", "x".repeat(256_001))) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(body.toResponseBody()).build()
            }.build()
            val client = ChatGptTranscriptionClient({ CodexHeaders("token", null, null) }, http)
            assertThat(runCatching { client.transcribe(recording(), "auto") }.exceptionOrNull())
                .isInstanceOf(DictationException::class.java)
        }
    }
}
