package dev.aster.probe

import android.content.Context
import org.json.JSONArray
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
        /** The models the catalog suggests here, best first; the list to fall back on when `/models` is out of reach. */
        val recommended: List<String>,
        val needsKey: Boolean,
    ) {
        /** On a phone, localhost is the phone, where no model server runs unless someone put one there. */
        val local: Boolean get() = Lan.host(baseUrl)?.let(Lan::isLoopbackHost) ?: false
        /** The address in `.env`, rather than a row of the catalog. */
        val custom: Boolean get() = id == CUSTOM
    }

    /**
     * Rows with an unfilled `{placeholder}` in the URL cannot be reached from
     * here, and a picker entry that can only fail is worse than no entry. The
     * free gateway is always there, even when the catalog synced from the aster
     * repo predates it, because it is the brain a fresh install starts on.
     */
    fun catalog(context: Context): List<Provider> = runCatching {
        val text = context.assets.open("providers.json").bufferedReader().use { it.readText() }
        val rows = JSONObject(text).getJSONArray("providers")
        val read = (0 until rows.length())
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
                    keyVars = strings(row.optJSONArray("key_env")),
                    recommended = strings(row.optJSONArray("recommended")),
                    needsKey = !(auth.contains("none") || auth.contains("optional")),
                )
            }
            .filter { it.model.isNotEmpty() && !it.model.contains('{') }
        if (read.any { it.id == FREE }) read else read + FREE_ROW
    }.getOrDefault(listOf(FREE_ROW))

    /**
     * Usable first, then the shortlist in its order, then by name. The
     * shortlist is what works on a phone today; the other thirty rows are
     * still there, after it.
     */
    fun offered(context: Context): List<Provider> {
        val keys = present(context)
        val all = listOfNotNull(custom(context)) + catalog(context)
        return all.sortedWith(
            compareByDescending<Provider> { usable(it, keys) }
                .thenBy { SHORTLIST.indexOf(it.id).let { at -> if (at < 0) SHORTLIST.size else at } }
                .thenBy { it.name.lowercase() },
        )
    }

    fun usable(provider: Provider, keys: Set<String>): Boolean =
        !provider.needsKey || provider.keyVars.any { it in keys }

    /**
     * The stored pick, else an address typed into `.env`, else a provider the
     * phone holds a key for, else the free gateway. Never a localhost row on
     * its own: that points the agent at the phone itself, where it fails.
     */
    fun current(context: Context): Provider? {
        val stored = prefs(context).getString(PROVIDER, null)
        val offered = offered(context)
        offered.firstOrNull { it.id == stored }?.let { return it }
        offered.firstOrNull { it.custom }?.let { return it }
        val keys = present(context)
        return offered.firstOrNull { it.needsKey && usable(it, keys) && !it.local }
            ?: offered.firstOrNull { it.id == FREE }
    }

    /** Whether anyone has picked a provider yet, as opposed to the fallback in [current] standing in. */
    fun picked(context: Context): Boolean = prefs(context).getString(PROVIDER, null) != null

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

    /** The house default on OpenRouter; elsewhere the catalog's example, or what `.env` names. */
    fun currentModel(context: Context, provider: Provider): String =
        prefs(context).getString("$MODEL:${provider.id}", null)
            ?.takeIf { it.isNotBlank() }
            ?: if (provider.id == "openrouter") DEFAULT_MODEL else provider.model

    /**
     * Any OpenAI-compatible server: its address, the model to ask for, and a
     * key when it wants one. Written to `.env` in one go, since an address
     * without its model is a request that fails, and picked so [apply] hands it
     * on rather than the catalog's choice. The model is also stored as the
     * pick, because a pick left from an earlier server would otherwise win.
     */
    fun chooseCustom(context: Context, url: String, model: String, key: String?) {
        Env.setAll(context, customValues(url, model, key))
        pickCustom(context, model)
    }

    /** The `.env` lines for a custom server; a blank key drops a stale one. */
    fun customValues(url: String, model: String, key: String?): Map<String, String> =
        mapOf(BASE_URL to url.trim(), MODEL_VAR to model.trim(), SHARED_KEY to key?.trim().orEmpty())

    /** Make the server in `.env` the pick, for when its lines were written in some larger batch. */
    fun pickCustom(context: Context, model: String) {
        prefs(context).edit()
            .putString(PROVIDER, CUSTOM)
            .putString("$MODEL:$CUSTOM", model.trim())
            .apply()
    }

    /**
     * The no-account door: the gateway's best free model that takes images and
     * calls tools, read from its live list because free ids come and go by the
     * week. Network, so never on the main thread. Returns the model picked.
     */
    fun chooseFree(context: Context): String {
        val provider = catalog(context).first { it.id == FREE }
        val listed = fetch(provider.baseUrl.trimEnd('/') + "/models", emptyMap())
            ?.takeIf { it.code == 200 }
            ?.let { pickFree(it.body) }
        val model = listed ?: provider.model
        choose(context, provider)
        chooseModel(context, provider, model)
        return model
    }

    /**
     * From a gateway's `/models` reply, the free model to run the phone on:
     * the house pick if it is listed, else any free one that sees images and
     * calls tools, else the text-only fallback, else any free one with tools.
     * Routers are passed over, because what they route to changes underneath.
     */
    fun pickFree(body: String): String? = runCatching {
        val rows = JSONObject(body).getJSONArray("data")
        val free = (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it) }
            .filter { row -> row.optString("id").let { it.endsWith(":free") || it.endsWith("/free") } }
        val tools = free.filter { "tools" in strings(it.optJSONArray("supported_parameters")) }
        val vision = tools.filter { row ->
            "image" in strings(row.optJSONObject("architecture")?.optJSONArray("input_modalities"))
        }
        val direct = vision.filterNot { row -> ROUTERS.any { row.optString("id").startsWith(it) } }
        direct.firstOrNull { it.optString("id") == FREE_MODEL }?.optString("id")
            ?: direct.firstOrNull()?.optString("id")
            ?: tools.firstOrNull { it.optString("id") == FREE_TEXT_MODEL }?.optString("id")
            ?: tools.firstOrNull()?.optString("id")
    }.getOrNull()

    /** One model as the provider lists it: the id it is called by, and a name for people. */
    data class Model(val id: String, val name: String)

    /**
     * What the provider actually serves, from its own `/models` endpoint,
     * the way the IDE picker fills in. The catalog only carries one example
     * per provider, which is not a list to choose from. Without a key the free
     * gateway only serves its free models, so only those are offered.
     */
    fun models(context: Context, provider: Provider): List<Model> = runCatching {
        val key = key(context, provider)
        val reply = fetch(provider.baseUrl.trimEnd('/') + "/models", headers(provider, key)) ?: return emptyList()
        if (reply.code != 200) return emptyList()
        val rows = JSONObject(reply.body).getJSONArray("data")
        (0 until rows.length())
            .map { rows.getJSONObject(it) }
            .mapNotNull { row ->
                val id = row.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (provider.id == FREE && key == null && !(id.endsWith(":free") || id.endsWith("/free"))) {
                    return@mapNotNull null
                }
                val name = row.optString("name").ifEmpty { row.optString("display_name") }.substringAfter(": ")
                Model(id, name.takeIf { it.isNotEmpty() } ?: pretty(id))
            }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * Whether [key] opens [baseUrl], asked the cheap way: by listing models,
     * or on OpenRouter by asking about the key, since its model list answers
     * anyone. Null when it does, otherwise a sentence saying what came back.
     * NVIDIA's list is open too and it has no key endpoint, so its keys are
     * taken as given rather than reported as checked.
     */
    fun check(baseUrl: String, providerId: String, key: String?): String? {
        if (providerId in UNCHECKABLE) return null
        val path = if (providerId == "openrouter") "/key" else "/models"
        val reply = fetch(baseUrl.trimEnd('/') + path, headers(providerId, key?.takeIf { it.isNotBlank() }))
            ?: return "Droid could not reach ${Lan.host(baseUrl) ?: baseUrl}."
        return when (reply.code) {
            200 -> null
            // Gemini answers a bad key with 400 rather than 401.
            400, 401, 403 -> "The key was refused (HTTP ${reply.code}). Check it and try again."
            else -> "The server answered HTTP ${reply.code}."
        }
    }

    /**
     * Platform HTTP for https, and the LAN client for plain http to a private
     * address. Cleartext anywhere else is refused before a byte is sent.
     */
    private fun fetch(url: String, headers: Map<String, String>): Lan.Response? {
        if (url.startsWith("http://", ignoreCase = true)) return Lan.get(url, headers, timeoutMs = FETCH_TIMEOUT_MS)
        return runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Lan.Response(code, body)
        }.getOrNull()
    }

    private fun headers(provider: Provider, key: String?) = headers(provider.id, key)

    /** Anthropic wants its own header and a version; everyone else takes a bearer. */
    private fun headers(providerId: String, key: String?): Map<String, String> = when {
        key == null -> emptyMap()
        providerId == "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)
        else -> mapOf("Authorization" to "Bearer $key")
    }

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
     *
     * The key also goes out as `ASTER_API_KEY`. aster only finds a provider's
     * own variable for endpoints it knows, and only skips the key for
     * loopback, so a LAN server or the free gateway would otherwise stop it at
     * "set a key". A keyless endpoint gets a placeholder, which also keeps a
     * key meant for some other server from being sent to it.
     *
     * The vision fallback defaults to a model only OpenRouter serves, so
     * elsewhere it is pointed at the session model unless `.env` names one.
     */
    fun apply(context: Context, env: MutableMap<String, String>) {
        val provider = current(context) ?: return
        val model = currentModel(context, provider)
        env[BASE_URL] = provider.baseUrl
        env[MODEL_VAR] = model
        currentEffort(context).takeIf { it.isNotEmpty() }?.let { env["ASTER_EFFORT"] = it }
        val key = key(context, provider)
        when {
            key != null -> env[SHARED_KEY] = key
            !provider.needsKey -> env[SHARED_KEY] = if (provider.id == FREE) ANONYMOUS_KEY else LOCAL_KEY
        }
        if (provider.id != "openrouter" && VISION_VAR !in Env.all(context)) env[VISION_VAR] = model
    }

    /** Which key names the device actually holds, read once per pass. */
    fun present(context: Context): Set<String> = Env.all(context).keys

    /** The server named in `.env`, offered as a provider of its own. */
    private fun custom(context: Context): Provider? {
        val values = Env.all(context)
        val url = values[BASE_URL] ?: return null
        val host = Lan.host(url) ?: return null
        val kind = Lan.Kind.entries.firstOrNull { it.port == Lan.port(url) }
        val where = if (Lan.isLoopbackHost(host)) "this phone" else host
        return Provider(
            id = CUSTOM,
            name = kind?.let { "${it.label} on $where" } ?: "Your server at $where",
            baseUrl = url,
            model = values[MODEL_VAR].orEmpty(),
            keyVars = listOf(SHARED_KEY),
            recommended = emptyList(),
            needsKey = false,
        )
    }

    private fun strings(array: JSONArray?): List<String> =
        array?.let { list -> (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotEmpty) } }
            .orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences("models", Context.MODE_PRIVATE)

    /** The free gateway's catalog id, and the door onboarding opens first. */
    const val FREE = "kilo"
    const val CUSTOM = "custom"
    const val BASE_URL = "ASTER_BASE_URL"
    const val MODEL_VAR = "ASTER_MODEL"
    /** The key aster falls back on for any endpoint it has no variable of its own for. */
    const val SHARED_KEY = "ASTER_API_KEY"
    private const val VISION_VAR = "ASTER_VISION_MODEL"
    /** What aster itself sends to a keyless loopback server. */
    private const val LOCAL_KEY = "local"
    /**
     * The gateway serves free models with no key, but refuses a made-up one
     * such as "local" with 401, and aster always sends some bearer. This one
     * it takes as no key at all (tested 2026-10-22; not in Kilo's docs, which
     * only say unauthenticated requests work). The lasting fix is aster sending
     * no header for keyless endpoints.
     */
    private const val ANONYMOUS_KEY = "anonymous"
    private const val FREE_MODEL = "nvidia/nemotron-3.5-super-vl:free"
    private const val FREE_TEXT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free"
    private val ROUTERS = listOf("kilo-auto/", "openrouter/")
    /** Providers whose model list answers without a key, so listing it proves nothing about one. */
    private val UNCHECKABLE = setOf("nvidia_nim", FREE)

    /** The no-key gateway, for when the catalog in the build has no row for it. */
    private val FREE_ROW = Provider(
        id = FREE,
        name = "Kilo Gateway (free, no account)",
        baseUrl = "https://ai-gateway.kilo.ai/api/v1",
        model = FREE_MODEL,
        keyVars = listOf("KILO_API_KEY"),
        recommended = listOf(FREE_MODEL, FREE_TEXT_MODEL),
        needsKey = false,
    )

    /**
     * What works on a phone today, in the order onboarding offers it: free
     * first, then keys with a free tier, then paid. GitHub Models is retired
     * and Groq's free tier is smaller than one request from this agent, so
     * neither is here.
     */
    val SHORTLIST = listOf(
        FREE, "openrouter", "google_gemini", "nvidia_nim", "mistral",
        "deepseek", "zai", "moonshot", "anthropic", "openai",
    )

    private const val PROVIDER = "provider"
    private const val MODEL = "model"
    private const val EFFORT = "effort"
    val EFFORTS = listOf("off", "low", "medium", "high", "xhigh", "max", "ultra")
    private const val DEFAULT_MODEL = "z-ai/glm-5.3-flash"
    private const val FETCH_TIMEOUT_MS = 10_000
    private const val ANTHROPIC_VERSION = "2023-06-01"
}
