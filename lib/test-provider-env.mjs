// pi reads provider credentials straight from the environment (pi-ai's own key list, plus
// Vertex ADC and the proxy variables a machine running Claude Code exports). A test that wants
// a runtime where only the provider it configured is available therefore has to take all of
// them away first: with `ANTHROPIC_AUTH_TOKEN` in the shell, `getAvailable()` answers the whole
// built-in Anthropic catalog and asserts written against a bare list stop matching. Test-only:
// nothing in production imports this.

/** Names pi treats as a provider being configured — API keys, tokens, and the Vertex ADC that
 *  decides whether `vertex` counts as available. */
export const PROVIDER_ENV_KEYS = [
  "AI_GATEWAY_API_KEY",
  "ANTHROPIC_API_KEY",
  "ANTHROPIC_AUTH_TOKEN",
  "ANTHROPIC_BASE_URL",
  "ANTHROPIC_OAUTH_TOKEN",
  "ANT_LING_API_KEY",
  "AWS_ACCESS_KEY_ID",
  "AWS_BEARER_TOKEN_BEDROCK",
  "AWS_PROFILE",
  "AWS_SECRET_ACCESS_KEY",
  "AWS_SESSION_TOKEN",
  "AZURE_OPENAI_API_KEY",
  "BASETEN_API_KEY",
  "CEREBRAS_API_KEY",
  "CLOUDFLARE_API_KEY",
  "COPILOT_GITHUB_TOKEN",
  "DEEPSEEK_API_KEY",
  "FIREWORKS_API_KEY",
  "GEMINI_API_KEY",
  "GOOGLE_APPLICATION_CREDENTIALS",
  "GOOGLE_CLOUD_API_KEY",
  "GOOGLE_CLOUD_LOCATION",
  "GOOGLE_CLOUD_PROJECT",
  "GROQ_API_KEY",
  "GCLOUD_PROJECT",
  "HF_TOKEN",
  "KIMI_API_KEY",
  "META_API_KEY",
  "MINIMAX_API_KEY",
  "MINIMAX_CN_API_KEY",
  "MISTRAL_API_KEY",
  "MOONSHOT_API_KEY",
  "NVIDIA_API_KEY",
  "OPENAI_API_KEY",
  "OPENAI_BASE_URL",
  "OPENCODE_API_KEY",
  "OPENROUTER_API_KEY",
  "QWEN_TOKEN_PLAN_API_KEY",
  "QWEN_TOKEN_PLAN_CN_API_KEY",
  "RADIUS_API_KEY",
  "TOGETHER_API_KEY",
  "TYPESAFE_API_KEY",
  "XIAOMI_API_KEY",
  "XIAOMI_TOKEN_PLAN_AMS_API_KEY",
  "XIAOMI_TOKEN_PLAN_CN_API_KEY",
  "XIAOMI_TOKEN_PLAN_SGP_API_KEY",
  "XAI_API_KEY",
  "ZAI_API_KEY",
  "ZAI_CODING_CN_API_KEY",
];

/** Removes them from `process.env` and returns the names it removed for `restoreProviderEnv`. */
export function stripProviderEnv(keys = PROVIDER_ENV_KEYS) {
  const removed = [];
  for (const key of keys) {
    if (key in process.env) {
      removed.push([key, process.env[key]]);
      delete process.env[key];
    }
  }
  return removed;
}

export function restoreProviderEnv(removed) {
  for (const [key, value] of removed) process.env[key] = value;
}
