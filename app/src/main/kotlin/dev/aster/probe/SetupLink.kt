package dev.aster.probe

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject

/**
 * A whole setup in one link or QR code: the brain, its key, the chat app and
 * who may use it, made on the website and opened on the phone, so nobody types
 * a 60-character key with their thumbs.
 *
 * `https://droid.withaster.dev/s#1.<data>` or `asterdroid://setup#1.<data>`,
 * where data is base64url of raw-deflated JSON. The payload lives in the
 * fragment, which a browser never sends to a server.
 *
 * Any web page or QR code can open this link, so nothing in it is trusted:
 * every field is checked here, the names it may write are fixed by this code
 * rather than by the link, and the person always sees what it will change
 * before anything is written.
 */
object SetupLink {

    /** Which model answers, and the key that opens it. */
    data class Brain(
        val id: String,
        val url: String,
        val model: String?,
        val keyVar: String?,
        val key: String?,
        val effort: String?,
    )

    /** Where people talk to the agent once it runs. */
    sealed interface Reach {
        data class Telegram(val token: String, val users: String?) : Reach
        data class Photon(val key: String, val project: String, val users: String?) : Reach
        data object App : Reach
    }

    /** A browser asking to be paired to the relay in the same scan. */
    data class Claim(val pub: String, val nonce: String, val name: String) {
        fun link(relay: String): String =
            "${relay.trimEnd('/')}/claim#1.$pub.$nonce.${B64.encodeToString(name.toByteArray())}"
    }

    data class Setup(
        val nonce: String,
        val expires: Long,
        val brain: Brain?,
        val reach: Reach?,
        val mcp: Boolean?,
        val relayUrl: String?,
        val claim: Claim?,
    )

    sealed interface Parsed {
        data class Ok(val setup: Setup) : Parsed
        data class Bad(val why: String) : Parsed
    }

    /** Whether [text] is meant to be one of these, before it is checked at all. */
    fun isSetupLink(text: String): Boolean {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        return scheme == "https" && host == WEB_HOST && uri.rawPath.orEmpty() in WEB_PATHS ||
            scheme == APP_SCHEME && host == APP_HOST
    }

    /**
     * Read and check a link. [now] is in seconds; [seen] holds the nonces of
     * links already applied, so a screenshot of one cannot be replayed.
     */
    fun parse(link: String, now: Long, seen: Set<String>): Parsed {
        if (!isSetupLink(link)) return Parsed.Bad("This is not a Droid setup link.")
        val fragment = runCatching { URI(link.trim()).rawFragment }.getOrNull()
            ?: return Parsed.Bad("This setup link is missing its settings. Copy the whole link and try again.")
        if (!fragment.startsWith("$VERSION.")) return Parsed.Bad("This setup link is from a newer Droid. Update the app and try again.")
        val data = fragment.removePrefix("$VERSION.")
        if (data.isEmpty() || data.length > MAX_ENCODED) return Parsed.Bad("This setup link is damaged.")
        val json = decode(data)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Parsed.Bad("This setup link is damaged.")
        return runCatching { Parsed.Ok(read(json, now, seen)) }
            .getOrElse { Parsed.Bad(it.message ?: "This setup link is damaged.") }
    }

    /** base64url, then raw inflate, capped so a small link cannot unpack into megabytes. */
    fun decode(data: String): String? = runCatching {
        val packed = Base64.getUrlDecoder().decode(data)
        val inflater = Inflater(true)
        inflater.setInput(packed)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_JSON) return null
        }
        inflater.end()
        out.toString(Charsets.UTF_8.name())
    }.getOrNull()

    private fun read(json: JSONObject, now: Long, seen: Set<String>): Setup {
        if (json.optInt("v", -1) != VERSION) fail("This setup link is from a newer Droid. Update the app and try again.")
        val exp = json.optLong("exp", 0L)
        if (exp <= 0L) fail("This setup link has no expiry, so Droid will not use it.")
        if (exp < now) fail("This setup link has expired. Make a new one and scan it within 15 minutes.")
        if (exp > now + MAX_LIFE_S + SKEW_S) fail("This setup link stays valid for more than 15 minutes, so Droid will not use it.")
        val nonce = json.optString("n")
        if (!NONCE.matches(nonce)) fail("This setup link is damaged.")
        if (nonce in seen) fail("This setup link was already used. Make a new one.")
        val relayUrl = json.optJSONObject("relay")?.optString("url")?.takeIf { it.isNotEmpty() }?.also {
            if (!Relay.usable(it)) fail("The relay address in this link is not https, so Droid will not use it.")
        }?.takeIf(Relay::selfHosted)
        val mcp = json.optJSONObject("mcp")?.let { it.opt("on") as? Boolean }
        return Setup(
            nonce = nonce,
            expires = exp,
            brain = json.optJSONObject("p")?.let(::brain),
            reach = json.optJSONObject("ch")?.let(::reach),
            mcp = mcp,
            relayUrl = relayUrl,
            claim = json.optJSONObject("claim")?.let(::claim),
        )
    }

    private fun brain(p: JSONObject): Brain {
        val id = p.optString("id")
        if (!ID.matches(id)) fail("The provider in this link is not one Droid can read.")
        val url = p.optString("url")
        if (url.isNotEmpty() && !Lan.allowedUrl(url)) {
            fail("The model address in this link is plain http to the internet. Droid only sends keys over https, or to a computer on your own network.")
        }
        val model = p.optString("m").takeIf { it.isNotEmpty() }?.also {
            if (!MODEL.matches(it)) fail("The model name in this link is not one Droid can read.")
        }
        val keyVar = p.optString("kv").takeIf { it.isNotEmpty() }?.also {
            if (!NAME.matches(it) || forbidden(it)) fail("This link tries to set $it, which a setup link may not change.")
        }
        val key = p.optString("k").takeIf { it.isNotEmpty() }?.also { clean(it, "key") }
        val effort = p.optString("e").takeIf { it.isNotEmpty() }?.also {
            if (it !in Models.EFFORTS) fail("The effort in this link is not one Droid knows.")
        }
        return Brain(id = id, url = url, model = model, keyVar = keyVar, key = key, effort = effort)
    }

    private fun reach(ch: JSONObject): Reach? {
        ch.optJSONObject("tg")?.let { tg ->
            val token = tg.optString("t")
            if (!TELEGRAM_TOKEN.matches(token)) fail("The Telegram token in this link does not look like one from @BotFather.")
            return Reach.Telegram(token, users(tg, "u"))
        }
        ch.optJSONObject("photon")?.let { photon ->
            val key = first(photon, "k", "key", "secret")
            val project = first(photon, "p", "project")
            if (!TOKENISH.matches(key) || !TOKENISH.matches(project)) {
                fail("This link needs both the Photon project key and the project id.")
            }
            return Reach.Photon(key, project, users(photon, "u", "users", "senders"))
        }
        if (ch.optBoolean("app")) return Reach.App
        return null
    }

    private fun claim(c: JSONObject): Claim {
        val pub = c.optString("pub")
        val nonce = c.optString("nonce")
        val name = c.optString("name").ifEmpty { "A browser" }.take(MAX_NAME)
        if (!B64URL.matches(pub) || !B64URL.matches(nonce) || nonce.length < 16) fail("The pairing part of this link is damaged.")
        clean(name, "browser name")
        return Claim(pub, nonce, name)
    }

    /** A list as the link may give it, an array or one comma-separated string, as `.env` wants it. */
    private fun users(o: JSONObject, vararg names: String): String? {
        for (name in names) {
            val raw = o.opt(name) ?: continue
            val joined = when (raw) {
                is JSONArray -> (0 until raw.length()).joinToString(",") { raw.optString(it).trim() }
                else -> raw.toString()
            }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            if (joined.isEmpty()) return null
            if (!USERS.matches(joined)) fail("The allowed people in this link are not ids or handles Droid can read.")
            return joined
        }
        return null
    }

    private fun first(o: JSONObject, vararg names: String): String =
        names.firstNotNullOfOrNull { o.optString(it).takeIf(String::isNotEmpty) }.orEmpty()

    /** A value becomes one `.env` line, so a line break in it would write a second name of the link's choosing. */
    private fun clean(value: String, what: String) {
        if (value.length > MAX_VALUE || value.any { it.isISOControl() } || value.trim() != value) {
            fail("The $what in this link has characters Droid will not write.")
        }
    }

    private fun fail(why: String): Nothing = throw IllegalArgumentException(why)

    /**
     * Names no link may set, however it spells them. These choose what runs,
     * where it listens, or where the dynamic linker looks, and every child
     * inherits the whole `.env`.
     */
    fun forbidden(name: String): Boolean {
        val upper = name.uppercase()
        return upper in DENIED || upper.startsWith("LD_") || upper.startsWith("DYLD_") ||
            upper.endsWith("_CMD") || upper.endsWith("_BIND") || upper.endsWith("PATH")
    }

    /** The catalog row the link's brain is, or null when it points somewhere the catalog does not. */
    fun known(brain: Brain, catalog: List<Models.Provider>): Models.Provider? {
        val row = catalog.firstOrNull { it.id == brain.id && !it.custom } ?: return null
        return row.takeIf { brain.url.isEmpty() || same(brain.url, it.baseUrl) }
    }

    private fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    /**
     * Everything the link writes to `.env`, as one batch. Which names a key
     * may go under comes from the catalog, never from the link: a known
     * provider's own variables, or `ASTER_API_KEY` for any other address.
     * The relay goes on only for one the person hosts: the link's own, or
     * [ownRelay] when the link pairs a computer, since Aster Relay is not
     * open yet.
     */
    fun values(
        setup: Setup,
        catalog: List<Models.Provider>,
        mcpToken: String?,
        ownRelay: String? = null,
        newToken: () -> String,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        setup.brain?.let { brain ->
            val row = known(brain, catalog)
            if (row != null) {
                if (brain.key != null) {
                    val name = brain.keyVar ?: row.keyVars.firstOrNull()
                    if (name == null || name !in row.keyVars) fail("This link puts the key under a name ${row.name} does not use.")
                    out[name] = brain.key
                }
            } else {
                if (brain.url.isEmpty()) fail("This link names a provider Droid does not know and gives no address for it.")
                if (brain.keyVar != null && brain.keyVar != Models.SHARED_KEY) {
                    fail("This link puts the key under ${brain.keyVar}, which is not where Droid keeps a key for its own address.")
                }
                out.putAll(Models.customValues(brain.url, brain.model.orEmpty(), brain.key))
            }
        }
        when (val reach = setup.reach) {
            is Reach.Telegram -> {
                out[Env.CHANNEL] = Channel.TELEGRAM.id
                out[Env.TELEGRAM_TOKEN] = reach.token
                reach.users?.let { out[Env.REMOTE_USERS] = it }
            }
            is Reach.Photon -> {
                out[Env.CHANNEL] = Channel.PHOTON.id
                out[Env.PHOTON_KEY] = reach.key
                out[Env.PHOTON_PROJECT] = reach.project
                reach.users?.let { out[Env.REMOTE_USERS] = it }
            }
            Reach.App -> out[Env.CHANNEL] = Channel.APP.id
            null -> Unit
        }
        when (setup.mcp) {
            true -> out[Env.MCP_TOKEN] = mcpToken?.takeIf { it.length >= MIN_TOKEN } ?: newToken()
            false -> out[Env.MCP_TOKEN] = ""
            null -> Unit
        }
        setup.relayUrl?.let { out[Env.RELAY_URL] = it }
        if (setup.relayUrl != null || setup.claim != null && Relay.selfHosted(ownRelay)) out[Env.RELAY] = "on"
        return out
    }

    /** Why [values] would refuse this link against [catalog], or null when it would not; asked before the sheet opens. */
    fun check(setup: Setup, catalog: List<Models.Provider>): String? =
        runCatching { values(setup, catalog, null) { "" } }.exceptionOrNull()?.message

    /**
     * Write the link in one batch and pick its model, then forget nothing:
     * the nonce goes on the seen list so the same link cannot be used again.
     * The caller restarts the agent once, after this.
     */
    fun apply(context: Context, setup: Setup) {
        val catalog = Models.catalog(context)
        Env.setAll(context, values(setup, catalog, Env.value(context, Env.MCP_TOKEN), Env.value(context, Env.RELAY_URL), ::token))
        setup.brain?.let { brain ->
            val row = known(brain, catalog)
            if (row != null) {
                Models.choose(context, row)
                brain.model?.let { Models.chooseModel(context, row, it) }
            } else {
                Models.pickCustom(context, brain.model.orEmpty())
            }
            brain.effort?.let { Models.chooseEffort(context, it) }
        }
        remember(context, setup.nonce)
    }

    /** A fresh key for MCP or ACP: 32 random bytes as 64 hex characters. */
    fun token(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Nonces applied in the last day, the newest few dozen. */
    fun seen(context: Context): Set<String> = stamps(context).keys

    private fun remember(context: Context, nonce: String) {
        val now = System.currentTimeMillis()
        val kept = (stamps(context) + (nonce to now))
            .filterValues { now - it < SEEN_FOR_MS }
            .entries.sortedByDescending { it.value }
            .take(SEEN_MAX)
        val json = JSONObject().apply { kept.forEach { put(it.key, it.value) } }
        prefs(context).edit().putString(SEEN, json.toString()).apply()
    }

    private fun stamps(context: Context): Map<String, Long> = runCatching {
        val json = JSONObject(prefs(context).getString(SEEN, null) ?: return emptyMap())
        json.keys().asSequence().associateWith { json.optLong(it) }
    }.getOrDefault(emptyMap())

    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    private val B64 = Base64.getUrlEncoder().withoutPadding()

    private const val VERSION = 1
    private const val WEB_HOST = "droid.withaster.dev"
    private val WEB_PATHS = setOf("/s", "/s/")
    private const val APP_SCHEME = "asterdroid"
    private const val APP_HOST = "setup"
    private const val MAX_LIFE_S = 15 * 60L
    /** A phone's clock a little behind the laptop's should not refuse a fresh link. */
    private const val SKEW_S = 60L
    private const val MAX_ENCODED = 8 * 1024
    private const val MAX_JSON = 32 * 1024
    private const val MAX_VALUE = 4 * 1024
    private const val MAX_NAME = 80
    private const val MIN_TOKEN = 32
    private const val SEEN = "seen"
    private const val SEEN_MAX = 64
    private const val SEEN_FOR_MS = 24 * 60 * 60 * 1000L

    private val NONCE = Regex("[A-Za-z0-9_-]{8,64}")
    private val ID = Regex("[a-z0-9_]{1,40}")
    private val NAME = Regex("[A-Z][A-Z0-9_]{1,63}")
    private val MODEL = Regex("[A-Za-z0-9._:/@+-]{1,200}")
    private val TELEGRAM_TOKEN = Regex("[0-9]{5,15}:[A-Za-z0-9_-]{20,80}")
    private val TOKENISH = Regex("[A-Za-z0-9_-]{4,256}")
    private val USERS = Regex("[A-Za-z0-9_@+.,-]{1,512}")
    private val B64URL = Regex("[A-Za-z0-9_-]{1,128}")
    private val DENIED = setOf(
        Env.AGENT_CMD, Env.MCP_BIND, Env.ACP_BIND, Env.MCP_PORT, Env.ACP_PORT, Env.ACP_TOKEN,
        "PATH", "HOME", "TMPDIR", "SHELL", "ASTER_BIN", "ASTER_HOME", "ASTER_COMPACT_BUDGET",
        Models.BASE_URL, Models.MODEL_VAR, "ASTER_VISION_MODEL",
    )
}
