package ai.closepaw.qa

import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.settings.LlmAuthSettingsPage
import ai.closepaw.ui.settings.ModelLoadingStatus
import ai.closepaw.ui.settings.OpenAiAuthUiState
import ai.closepaw.ui.theme.ClosePawTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5-S8: LLM Auth page callback contract — tabs are inert until the user commits
 * an action inside the tab; provider sub-selector is also inert (no settings
 * writes) until a model is committed; commits canonicalize per Section 5.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLlmAuthTest {

    @get:Rule val compose = createComposeRule()

    /** Extended test catalog with an OPENAI_CODEX entry so OAuth-tab canonicalization has a target. */
    private fun catalog(): ModelCatalog = ModelCatalog.fromJson(
        """
        {
          "gpt-5.2": {"display_name": "GPT-5.2", "provider":"OPENAI_API", "api": "response", "model_id": "gpt-5.2"},
          "gpt-5.2-chat": {"display_name": "GPT-5.2 (Chat API)", "provider":"OPENAI_API", "api": "chat", "model_id": "gpt-5.2"},
          "gpt-5.2-codex": {"display_name": "GPT-5.2 Codex", "provider":"OPENAI_CODEX", "api": "response", "model_id": "gpt-5.2"},
          "glm-5": {"display_name": "GLM-5", "provider": "OPENROUTER", "api": "chat", "model_id": "z-ai/glm-5"},
          "autoglm": {"display_name": "AutoGLM", "provider": "OTHER", "api": "chat", "model_id": "zai-org/autoglm", "base_url": "https://example.invalid/v1"}
        }
        """.trimIndent()
    )

    @Composable
    private fun AuthPage(
        llmBackend: LLMBackendType = LLMBackendType.OPENAI,
        onBackendChange: (LLMBackendType) -> Unit = {},
        selectedModel: String = "gpt-5.2",
        onModelChange: (String) -> Unit = {},
        onStartOAuth: () -> Unit = {},
        initialAuthTab: AuthMode? = null,
    ) {
        ClosePawTheme {
            LlmAuthSettingsPage(
                llmBackend = llmBackend,
                onBackendChange = onBackendChange,
                selectedModel = selectedModel,
                onModelChange = onModelChange,
                modelCatalog = catalog(),
                selectedLocalModel = "LFM2.5-1.2B-Instruct",
                onLocalModelChange = {},
                modelLoadingStatus = ModelLoadingStatus.Idle,
                openAiAuthUiState = OpenAiAuthUiState.SignedOut,
                onStartOAuth = onStartOAuth,
                onCancelOAuth = {},
                onSignOut = {},
                onBack = {},
                onClose = {},
                initialAuthTab = initialAuthTab,
            )
        }
    }

    @Test fun only_subscription_controls_are_visible_even_for_old_api_deep_link() {
        compose.setContent { AuthPage(initialAuthTab = AuthMode.ApiKey) }
        compose.onNodeWithText("Sign in with ChatGPT").assertExists()
        for (label in listOf("API Key", "OpenAI Key", "OpenRouter", "Other", "Local")) {
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    @Test fun oauth_start_migrates_old_model_to_subscription() {
        var model: String? = null
        var backend: LLMBackendType? = null
        var starts = 0
        compose.setContent {
            AuthPage(selectedModel = "gpt-5.2-chat", onModelChange = { model = it },
                onBackendChange = { backend = it }, onStartOAuth = { starts++ })
        }
        compose.onNodeWithText("Sign in with ChatGPT").performClick()
        assertEquals("gpt-5.2-codex", model)
        assertEquals(LLMBackendType.OPENAI, backend)
        assertEquals(1, starts)
    }

    @Test fun local_backend_also_shows_subscription_sign_in() {
        compose.setContent { AuthPage(llmBackend = LLMBackendType.LOCAL) }
        compose.onNodeWithText("Sign in with ChatGPT").assertExists()
        compose.onAllNodesWithText("API Key").assertCountEquals(0)
        compose.onAllNodesWithText("Local").assertCountEquals(0)
    }
}
