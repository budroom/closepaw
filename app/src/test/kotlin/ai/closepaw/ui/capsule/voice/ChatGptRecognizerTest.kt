package ai.closepaw.ui.capsule.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGptRecognizerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `stop transcribes recording once and deletes private audio`() = runTest {
        val file = temp.newFile()
        val events = mutableListOf<String>()
        val recognizer = ChatGptRecognizer(this, { file }, { _, stop -> stop.await() },
            { _, language -> events += language; "Hello" }, StandardTestDispatcher(testScheduler))
        recognizer.start("en-US", callbacks(events))
        runCurrent()
        recognizer.stop()
        advanceUntilIdle()
        assertThat(events).containsExactly("processing", "en-US", "Hello").inOrder()
        assertThat(file.exists()).isFalse()
    }

    @Test fun `destroy during upload cancels request and never inserts stale text`() = runTest {
        val file = temp.newFile()
        val events = mutableListOf<String>()
        val upload = CompletableDeferred<String>()
        val recognizer = ChatGptRecognizer(this, { file }, { _, stop -> stop.await() },
            { _, _ -> upload.await() }, StandardTestDispatcher(testScheduler))
        recognizer.start("en", callbacks(events))
        runCurrent()
        recognizer.stop()
        runCurrent()
        recognizer.destroy()
        upload.complete("stale")
        advanceUntilIdle()
        assertThat(events).containsExactly("processing")
        assertThat(file.exists()).isFalse()
    }

    @Test fun `cancel while recording releases recorder and deletes audio without uploading`() = runTest {
        val file = temp.newFile()
        val events = mutableListOf<String>()
        var released = false
        val recognizer = ChatGptRecognizer(this, { file }, { _, stop ->
            try { stop.await() } finally { released = true }
        }, { _, _ -> error("Cancelled audio must not upload") }, StandardTestDispatcher(testScheduler))
        recognizer.start("en", callbacks(events))
        runCurrent()
        recognizer.cancel()
        advanceUntilIdle()
        assertThat(released).isTrue()
        assertThat(file.exists()).isFalse()
        assertThat(events).isEmpty()
    }

    private fun callbacks(events: MutableList<String>) = object : RecognizerCallbacks {
        override fun onProcessing() { events += "processing" }
        override fun onPartial(text: String) { events += text }
        override fun onFinal(text: String) { events += text }
        override fun onError(error: VoiceError) { events += error.name }
    }
}
