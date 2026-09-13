package dev.aster.probe

import android.content.Context
import org.json.JSONObject

/**
 * Which provider and model answer. Left unset the binary falls back to a small
 * default and behaves like one: hedging, and handing steps back to someone who
 * is not here to take them. The catalog is the same `providers.json` the CLI
 * and desktop read, so the phone offers what they offer.
 */
object Models {

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val model: String,
        val keyVars: List<String>,
        val recommended: Boolean,
        val needsKey: Boolean,
    )

    /**
     * Rows with an unfilled `{placeholder}` in the URL cannot be reached from
     * here, and a picker entry that can only fail is worse than no entry.
     */
    fun catalog(context: Context): List<Provider> = runCatching {
        val text = context.assets.open("providers.json").bufferedReader().use { it.readText() }
        val rows = JSONObject(text).getJSONArray("providers")
        (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .mapNotNull { row ->
                val url = row.optString("base_url")
                if (url.isEmpty() || url.contains('{')) return@mapNotNull null
                val auth = row.optString("auth").lowercase()
                Provider(
                    id = row.optString("id"),
                    name = row.optString("name"),
                    baseUrl = url,
                    model = row.optString("example_model"),
                    keyVars = row.optJSONArray("key_env")
                        ?.let { keys -> (0 until keys.length()).map { keys.getString(it) } }
                        .orEmpty(),
                    recommended = row.optBoolean("recommended"),
                    needsKey = !(auth.contains("none") || auth.contains("optional")),
                )
            }
            .filter { it.model.isNotEmpty() && !it.model.contains('{') }
    }.getOrDefault(emptyList())

    /** Usable first, then recommended, so the sensible choice is the near one. */
    fun offered(context: Context): List<Provider> {
        val keys = present(context)
        return catalog(context).sortedWith(
            compareByDescending<Provider> { usable(it, keys) }
                .thenByDescending { it.recommended }
                .thenBy { it.name },
        )
    }

    fun usable(provider: Provider, keys: Set<String>): Boolean =
        !provider.needsKey || provider.keyVars.any { it in keys }

    fun current(context: Context): Provider? {
        val stored = prefs(context).getString(PROVIDER, null)
        val offered = offered(context)
        return offered.firstOrNull { it.id == stored }
            ?: offered.firstOrNull { usable(it, present(context)) }
    }

    fun choose(context: Context, provider: Provider) {
        prefs(context).edit().putString(PROVIDER, provider.id).apply()
    }

    /** Remembered per provider, so switching back does not lose the pick. */
    fun chooseModel(context: Context, provider: Provider, model: String) {
        prefs(context).edit().putString("$MODEL:${provider.id}", model).apply()
    }

    /** How hard the model thinks; empty means the provider's default. */
    fun chooseEffort(context: Context, effort: String) {
        prefs(context).edit().putString(EFFORT, effort).apply()
    }

    fun currentEffort(context: Context): String = prefs(context).getString(EFFORT, null).orEmpty()

    /** The house default on OpenRouter; elsewhere the catalog's example. */
    fun currentModel(context: Context, provider: Provider): String =
        prefs(context).getString("$MODEL:${provider.id}", null)
            ?.takeIf { it.isNotBlank() }
            ?: if (provider.id == "openrouter") DEFAULT_MODEL else provider.model

    /** One model as the provider lists it: the id it is called by, and a name for people. */
    data class Model(val id: String, val name: String)

    /**
     * What the provider actually serves, from its own `/models` endpoint,
     * the way the IDE picker fills in. The catalog only carries one example
     * per provider, which is not a list to choose from.
     */
    fun models(context: Context, provider: Provider): List<Model> = runCatching {
        val key = key(context, provider)
        val url = java.net.URL(provider.baseUrl.trimEnd('/') + "/models")
        val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            if (key != null) setRequestProperty("Authorization", "Bearer $key")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val rows = JSONObject(body).getJSONArray("data")
        (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .mapNotNull { row ->
                val id = row.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = row.optString("name").substringAfter(": ")
                Model(id, name.takeIf { it.isNotEmpty() } ?: pretty(id))
            }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * `anthropic/claude-fable-5.1` reads as "Claude Fable 5.1"; the vendor is
     * already in the provider, and dashes are not how anyone says a name. The
     * same goes for the "Vendor: " prefix a router puts on its listed names.
     */
    fun pretty(id: String): String = id
        .substringAfterLast('/')
        .split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part ->
            when {
                part.lowercase() in ACRONYMS -> part.uppercase()
                part.first().isDigit() -> part
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }

    private val ACRONYMS = setOf("glm", "gpt", "ai", "xai", "nim", "llm", "o1", "o3", "o4", "r1", "v3", "v4")

    private fun key(context: Context, provider: Provider): String? =
        provider.keyVars.firstNotNullOfOrNull { name -> Env.value(context, name) }

    /**
     * `dotenvy` leaves variables that are already set alone, so putting these
     * in the child's environment beats whatever `.env` says without rewriting
     * the file that also holds the keys.
     */
    fun apply(context: Context, env: MutableMap<String, String>) {
        val provider = current(context) ?: return
        env["ASTER_BASE_URL"] = provider.baseUrl
        env["ASTER_MODEL"] = currentModel(context, provider)
        currentEffort(context).takeIf { it.isNotEmpty() }?.let { env["ASTER_EFFORT"] = it }
    }

    /** Which key names the device actually holds, read once per pass. */
    fun present(context: Context): Set<String> = Env.all(context).keys

    private fun prefs(context: Context) =
        context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private const val PROVIDER = "provider"
    private const val MODEL = "model"
    private const val EFFORT = "effort"
    val EFFORTS = listOf("off", "low", "medium", "high", "xhigh", "max", "ultra")
    private const val DEFAULT_MODEL = "z-ai/glm-5.3-flash"
    private const val FETCH_TIMEOUT_MS = 10_000
}
