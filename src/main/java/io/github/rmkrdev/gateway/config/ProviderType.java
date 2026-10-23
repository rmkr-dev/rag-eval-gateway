package io.github.rmkrdev.gateway.config;

/**
 * Wire protocol a provider speaks. Most hosted and self-hosted models (OpenAI, Azure OpenAI,
 * OpenRouter, Together AI, Groq, Mistral, Modal, Ollama, vLLM) speak the OpenAI-compatible
 * protocol, so they only need configuration. The others get native adapters.
 */
public enum ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    BEDROCK
}
