package ai.closepaw.llm

/** The app uses ChatGPT subscription credentials exclusively, including resumed sessions. */
object SubscriptionPolicy {
    const val DEFAULT_MODEL = "gpt-5.5-codex"

    fun modelFor(savedModel: String, catalog: ModelCatalog): String {
        val saved = catalog.resolveOrNull(savedModel)
        if (saved?.provider == LLMProvider.OPENAI_CODEX) return saved.name
        val models = catalog.modelsFor(LLMProvider.OPENAI_CODEX)
        return models.firstOrNull { it.modelId == (saved?.modelId ?: savedModel) }?.name
            ?: models.firstOrNull { it.name == DEFAULT_MODEL }?.name
            ?: models.lastOrNull()?.name
            ?: error("No ChatGPT subscription models available")
    }

    fun requireSubscription(provider: LLMProvider) {
        require(provider == LLMProvider.OPENAI_CODEX) {
            "ClosePaw uses a ChatGPT/Codex subscription. Select a subscription model in Settings."
        }
    }
}
