package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubscriptionPolicyTest {
    private val catalog = ModelCatalog.fromJson("""{
      "gpt-5.2":{"display_name":"API","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"},
      "gpt-5.2-codex":{"display_name":"Subscription","provider":"OPENAI_CODEX","api":"response","model_id":"gpt-5.2"},
      "gpt-5.5-codex":{"display_name":"Subscription","provider":"OPENAI_CODEX","api":"response","model_id":"gpt-5.5"},
      "other":{"display_name":"Other","provider":"OTHER","api":"chat","model_id":"other"}
    }""")

    @Test fun `migration preserves subscription selection and maps API model to subscription`() {
        assertThat(SubscriptionPolicy.modelFor("gpt-5.2-codex", catalog)).isEqualTo("gpt-5.2-codex")
        assertThat(SubscriptionPolicy.modelFor("gpt-5.2", catalog)).isEqualTo("gpt-5.2-codex")
        assertThat(SubscriptionPolicy.modelFor("gpt-5.5", catalog)).isEqualTo("gpt-5.5-codex")
        assertThat(SubscriptionPolicy.modelFor("other", catalog)).isEqualTo("gpt-5.5-codex")
        assertThat(SubscriptionPolicy.modelFor("removed-model", catalog)).isEqualTo("gpt-5.5-codex")
    }
}
