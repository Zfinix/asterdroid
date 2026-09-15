# Providers

The catalog ships with 40 providers. Pick one in the app's settings sheet, which shows the env variable it expects, grab a key from its page, and paste it in. Local servers need no key at all.

**Cloud providers**

| provider | get a key | env variable |
| --- | --- | --- |
| OpenAI | [platform.openai.com](https://platform.openai.com/api-keys) | `OPENAI_API_KEY` |
| ChatGPT (Codex plan) | [chatgpt.com](https://chatgpt.com) | OAuth via `aster login codex` |
| Azure OpenAI | [portal.azure.com](https://portal.azure.com) | `AZURE_OPENAI_API_KEY` |
| Anthropic | [console.anthropic.com](https://console.anthropic.com/settings/keys) | `ANTHROPIC_API_KEY` |
| Google Gemini | [aistudio.google.com](https://aistudio.google.com/apikey) | `GEMINI_API_KEY` |
| xAI | [console.x.ai](https://console.x.ai) | `XAI_API_KEY` |
| Mistral AI | [console.mistral.ai](https://console.mistral.ai/api-keys) | `MISTRAL_API_KEY` |
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com/api_keys) | `DEEPSEEK_API_KEY` |
| Moonshot AI (Kimi) | [platform.moonshot.ai](https://platform.moonshot.ai/console/api-keys) | `MOONSHOT_API_KEY` |
| Z.ai (GLM) | [z.ai](https://z.ai) | `ZAI_API_KEY` |
| Alibaba DashScope (Qwen) | [bailian.console.aliyun.com](https://bailian.console.aliyun.com) | `DASHSCOPE_API_KEY` |
| MiniMax | [platform.minimax.io](https://platform.minimax.io) | `MINIMAX_API_KEY` |
| Perplexity | [perplexity.ai](https://www.perplexity.ai/settings/api) | `PERPLEXITY_API_KEY` |
| Cohere | [dashboard.cohere.com](https://dashboard.cohere.com/api-keys) | `COHERE_API_KEY` |
| Groq | [console.groq.com](https://console.groq.com/keys) | `GROQ_API_KEY` |
| Meta AI | [llama.com](https://llama.com) | `META_AI_API_KEY` |

**Aggregators and gateways** (one key, many models)

| provider | get a key | env variable |
| --- | --- | --- |
| OpenRouter | [openrouter.ai](https://openrouter.ai/settings/keys) | `OPEN_ROUTER_API_KEY` |
| OrcaRouter | [orcarouter.ai](https://orcarouter.ai) | `ORCAROUTER_API_KEY` |
| Vercel AI Gateway | [vercel.com](https://vercel.com/dash) | `AI_GATEWAY_API_KEY` |
| Hugging Face Router | [huggingface.co](https://huggingface.co/settings/tokens) | `HF_TOKEN` |
| Together AI | [api.together.xyz](https://api.together.xyz/settings/api-keys) | `TOGETHER_API_KEY` |
| Fireworks AI | [fireworks.ai](https://fireworks.ai) | `FIREWORKS_API_KEY` |
| Cerebras | [cloud.cerebras.ai](https://cloud.cerebras.ai) | `CEREBRAS_API_KEY` |
| DeepInfra | [deepinfra.com](https://deepinfra.com/dash/api_keys) | `DEEPINFRA_API_KEY` |
| Hyperbolic | [hyperbolic.xyz](https://hyperbolic.xyz) | `HYPERBOLIC_API_KEY` |
| Nebius AI Studio | [studio.nebius.ai](https://studio.nebius.ai) | `NEBIUS_API_KEY` |
| SambaNova | [cloud.sambanova.ai](https://cloud.sambanova.ai) | `SAMBANOVA_API_KEY` |
| Novita AI | [novita.ai](https://novita.ai/dashboard/key) | `NOVITA_API_KEY` |
| Baseten | [build.baseten.co](https://build.baseten.co) | `BASETEN_API_KEY` |
| Lambda Inference | [cloud.lambda.ai](https://cloud.lambda.ai) | `LAMBDA_API_KEY` |
| NVIDIA NIM | [build.nvidia.com](https://build.nvidia.com) | `NVIDIA_API_KEY` |
| Cloudflare Workers AI | [dash.cloudflare.com](https://dash.cloudflare.com) | `CLOUDFLARE_API_TOKEN` |
| AWS Bedrock | [console.aws.amazon.com](https://console.aws.amazon.com/bedrock) | `AWS_BEARER_TOKEN_BEDROCK` |
| Cencori | [cencori.com](https://cencori.com) | `CENCORI_API_KEY` |
| v0 by Vercel | [v0.dev](https://v0.dev) | `V0_API_KEY` |
| LiteLLM Proxy (self-hosted) | [docs.litellm.ai](https://docs.litellm.ai) | `LITELLM_API_KEY` |

**Local, no key needed**

| server | docs | env variable |
| --- | --- | --- |
| Ollama | [ollama.com](https://ollama.com) | none |
| LM Studio | [lmstudio.ai](https://lmstudio.ai) | none |
| vLLM | [docs.vllm.ai](https://docs.vllm.ai) | `VLLM_API_KEY` (optional) |
| llama.cpp server | [github.com/ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | none |

For a local server, point `ASTER_BASE_URL` at it instead of picking a provider.

The provider catalog is the same `providers.json` the CLI and desktop read, synced from the Aster repo at build time. Model lists come from the selected provider's `/models` endpoint, so the phone offers what the terminal offers.