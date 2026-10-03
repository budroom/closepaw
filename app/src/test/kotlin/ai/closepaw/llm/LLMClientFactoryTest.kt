package ai.closepaw.llm

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.CodexHeaders
import ai.closepaw.auth.FakeSharedPreferences
import ai.closepaw.auth.MissingCredential
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LLMClientFactoryTest {

    private val catalogJson = """
        {
          "gpt-5.2": {
            "display_name": "GPT-5.2",
            "provider":"OPENAI_API",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-chat": {
            "display_name": "GPT-5.2 (Chat)",
            "provider":"OPENAI_API",
            "api": "chat",
            "model_id": "gpt-5.2"
          },
          "gpt-5.2-codex": {
            "display_name": "GPT-5.2 (ChatGPT sign-in)",
            "provider": "OPENAI_CODEX",
            "api": "response",
            "model_id": "gpt-5.2"
          },
          "glm-4.7": {
            "display_name": "GLM-4.7",
            "provider": "OPENROUTER",
            "api": "chat",
            "model_id": "zhipu-ai/glm-4.7"
          }
        }
    """.trimIndent()

    private val catalog = ModelCatalog.fromJson(catalogJson)
    private lateinit var context: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        fakePrefs = FakeSharedPreferences()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun realStore(): AuthStore = AuthStore(context, prefsProvider = { fakePrefs })

    @Test
    fun `rejects API and other providers even with stored keys`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        store.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey("sk-other"))
        val factory = LLMClientFactory(catalog, store)
        for (name in listOf("gpt-5.2", "gpt-5.2-chat", "glm-4.7")) {
            assertThrows(IllegalArgumentException::class.java) { factory.create(name) }
        }
    }

    @Test
    fun `create caches subscription clients per model name`() {
        val factory = LLMClientFactory(catalog, realStore())
        assertSame(factory.create("gpt-5.2-codex"), factory.create("gpt-5.2-codex"))
    }

    @Test
    fun `OPENAI_CODEX routes to CodexResponseClient with header supplier`() = runBlocking {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.generation(any()) } returns 0L
        val captured = CodexHeaders(accessToken = "acc-123", chatgptAccountId = "acct", email = "x@y")
        coEvery { store.codexHeaders(LLMProvider.OPENAI_CODEX) } returns captured

        val factory = LLMClientFactory(catalog, store)
        val client = factory.create("gpt-5.2-codex")

        assertTrue(client is CodexResponseClient)

        // Supplier should pull from AuthStore per invocation.
        val supplier = extractHeaderSupplier(client as CodexResponseClient)
        val headers = runBlocking { supplier.invoke() }
        assertEquals(captured, headers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create throws for unknown model`() {
        val factory = LLMClientFactory(catalog, realStore())
        factory.create("nonexistent-model")
    }

    @Test
    fun `subscription supplier rejects missing sign in without falling back to API keys`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey("sk-test"))
        val client = LLMClientFactory(catalog, store).create("gpt-5.2-codex") as CodexResponseClient
        val failure = runCatching { extractHeaderSupplier(client)() }.exceptionOrNull()
        assertTrue(failure is MissingCredential)
    }

    @Test
    fun `Codex generation bump invalidates cached client`() = runBlocking {
        val store = realStore()
        store.set(
            LLMProvider.OPENAI_CODEX,
            AuthCredential.OAuth("at-1", "rt", Long.MAX_VALUE, "a@x", null),
        )
        val factory = LLMClientFactory(catalog, store)

        val c1 = factory.create("gpt-5.2-codex")
        // Re-sign-in with a different account → bumps generation → factory must rebuild
        // even though provider is OPENAI_CODEX (header-supplier path).
        store.set(
            LLMProvider.OPENAI_CODEX,
            AuthCredential.OAuth("at-2", "rt", Long.MAX_VALUE, "b@x", null),
        )
        val c2 = factory.create("gpt-5.2-codex")

        assertNotSame(c1, c2)
    }

    @Test
    fun `concurrent create across set bump never returns stale client`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OPENAI_CODEX, AuthCredential.OAuth("at-0", "rt", Long.MAX_VALUE, null, null))
        val factory = LLMClientFactory(catalog, store)
        val c0 = factory.create("gpt-5.2-codex")

        // Two reader threads torture-test against a writer. After every bump, any
        // create() that observes the post-bump store must never return c0.
        val iterations = 200
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val startGate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(3)

        val writer = Runnable {
            try {
                startGate.await()
                repeat(iterations) { i ->
                    runBlocking {
                        store.set(LLMProvider.OPENAI_CODEX, AuthCredential.OAuth("at-${i + 1}", "rt", Long.MAX_VALUE, null, null))
                    }
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }
        val reader = Runnable {
            try {
                startGate.await()
                // After a few initial iterations, any client must be != c0 because the
                // writer has already bumped generation at least once.
                var seenNonStale = false
                repeat(iterations * 2) {
                    val c = factory.create("gpt-5.2-codex")
                    if (c !== c0) seenNonStale = true
                    // Strict invariant: once we've seen a post-bump client, we must
                    // never see c0 again (no regression to stale).
                    if (seenNonStale && c === c0) {
                        error("returned stale c0 after observing a post-bump client")
                    }
                }
            } catch (t: Throwable) { errors += t } finally { done.countDown() }
        }

        pool.submit(writer)
        pool.submit(reader)
        pool.submit(reader)
        startGate.countDown()
        done.await()
        pool.shutdown()

        if (errors.isNotEmpty()) throw errors.first()

        // Final state: generation matches latest writer bump → client must be fresh.
        val finalClient = factory.create("gpt-5.2-codex")
        assertNotSame(c0, finalClient)
    }

    @Test
    fun `catalog resolution returns correct model entry`() {
        val entry = catalog.resolve("glm-4.7")
        assertEquals(LLMProvider.OPENROUTER, entry.provider)
        assertEquals(ApiType.CHAT, entry.api)
        assertEquals("zhipu-ai/glm-4.7", entry.modelId)
        assertEquals("https://openrouter.ai/api/v1", entry.effectiveBaseUrl)
    }

    // ── OTHER provider ──────────────────────────────────────────────────

    private val otherCatalogWithBaseUrl = ModelCatalog.fromJson(catalogJson).withExtraEntries(
        listOf(
            ModelEntry(
                name = "other-custom",
                displayName = "user/custom",
                provider = LLMProvider.OTHER,
                api = ApiType.CHAT,
                modelId = "user/custom",
                contextWindow = 128_000,
                baseUrl = "https://other.example.invalid/v1",
                apiKeyEnv = null,
                supportsVision = false,
            )
        )
    )

    private val otherCatalogBlankBaseUrl = ModelCatalog.fromJson(catalogJson).withExtraEntries(
        listOf(
            ModelEntry(
                name = "other-broken",
                displayName = "user/custom",
                provider = LLMProvider.OTHER,
                api = ApiType.CHAT,
                modelId = "user/custom",
                contextWindow = 128_000,
                baseUrl = null,
                apiKeyEnv = null,
                supportsVision = false,
            )
        )
    )

    @Test
    fun `OTHER is rejected regardless of credentials or base URL`() = runBlocking {
        val store = realStore()
        store.set(LLMProvider.OTHER, AuthCredential.ApiKey("sk-other"))
        assertThrows(IllegalArgumentException::class.java) {
            LLMClientFactory(otherCatalogWithBaseUrl, store).create("other-custom")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LLMClientFactory(otherCatalogBlankBaseUrl, store).create("other-broken")
        }
        Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractHeaderSupplier(client: CodexResponseClient): suspend () -> CodexHeaders {
        val f = CodexResponseClient::class.java.getDeclaredField("headerSupplier")
        f.isAccessible = true
        return f.get(client) as suspend () -> CodexHeaders
    }
}
