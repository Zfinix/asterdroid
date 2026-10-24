package dev.aster.probe

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Questions for the running relay connector: a pairing code, the paired
 * clients, revoking one, and its status. It listens on the abstract socket
 * `aster-relay` and answers only this app's uid, one JSON line in and one
 * out, the same as `asterctl relay <verb>` sends. Talking to the socket here
 * saves starting a process per question.
 *
 * Every call blocks for up to the connector's own wait on the relay, so none
 * of them may run on the main thread.
 */
object Relay {

    /** A code for a computer to type, and the link its QR carries. [expires] is in seconds. */
    data class Code(val code: String, val expires: Long, val url: String) {
        /** `7K2Q9MXD` reads as `7K2Q-9MXD`, which is easier to copy by eye. */
        val shown: String get() = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code
    }

    data class Client(
        val id: String,
        val name: String,
        val kind: String,
        val scopes: List<String>,
        val created: Long,
        val lastSeen: Long,
    )

    data class Status(val online: Boolean, val phoneId: String?, val url: String?, val entitled: Boolean)

    /** What a question came back with: the answer, or the connector's reason in its own words. */
    sealed interface Reply<out T> {
        data class Ok<T>(val value: T) : Reply<T>
        data class Failed(val why: String) : Reply<Nothing>
    }

    fun pair(): Reply<Code> = ask(JSONObject().put("op", "pair")) { code(it) }

    fun clients(): Reply<List<Client>> = ask(JSONObject().put("op", "clients")) { clients(it) }

    /** One client by id, or every one of them with `all`. */
    fun revoke(id: String): Reply<Unit> = ask(JSONObject().put("op", "revoke").put("id", id)) { }

    fun status(): Reply<Status> = ask(JSONObject().put("op", "status")) { status(it) }

    /** A client's own QR, scanned on the phone. Scanning it is the consent, so there is no second prompt. */
    fun add(link: String): Reply<Unit> = ask(JSONObject().put("op", "add").put("link", link)) { }

    fun code(reply: String): Code {
        val json = JSONObject(reply)
        val code = json.optString("code")
        require(code.isNotEmpty()) { "The relay sent no code." }
        return Code(code, json.optLong("exp"), json.optString("url"))
    }

    fun clients(reply: String): List<Client> {
        val rows = JSONArray(reply)
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { row ->
            Client(
                id = row.optString("id"),
                name = row.optString("name").ifEmpty { "A client" },
                kind = row.optString("kind"),
                scopes = row.optJSONArray("scopes")?.let { s -> (0 until s.length()).map { s.optString(it) } }.orEmpty(),
                created = row.optLong("created"),
                lastSeen = row.optLong("last_seen"),
            )
        }
    }

    fun status(reply: String): Status {
        val json = JSONObject(reply)
        return Status(
            online = json.optBoolean("online"),
            phoneId = json.optString("phone_id").takeIf { it.isNotEmpty() && it != "null" },
            url = json.optString("url").takeIf { it.isNotEmpty() },
            entitled = json.optBoolean("entitled"),
        )
    }

    /** The connector's `{"error": ...}`, or null when [reply] is an answer. */
    fun error(reply: String): String? =
        runCatching { JSONObject(reply).optString("error").takeIf { it.isNotEmpty() } }.getOrNull()

    private fun <T> ask(request: JSONObject, read: (String) -> T): Reply<T> {
        val reply = runCatching { exchange(request.toString()) }.getOrElse {
            return Reply.Failed("The relay is not running. Turn on Reach from anywhere first.")
        }
        error(reply)?.let { return Reply.Failed(it) }
        return runCatching { Reply.Ok(read(reply)) }
            .getOrElse { Reply.Failed(it.message ?: "The relay sent an answer Droid could not read.") }
    }

    private fun exchange(line: String): String = LocalSocket(LocalSocket.SOCKET_STREAM).use { socket ->
        socket.connect(LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
        socket.soTimeout = WAIT_MS
        socket.outputStream.apply {
            write("$line\n".toByteArray())
            flush()
        }
        socket.inputStream.bufferedReader().readText().trim()
    }

    /** Aster Relay itself, which is coming soon; until it opens only a relay someone hosts is used. */
    const val DEFAULT_URL = "https://relay.withaster.dev"

    /** Whether [url] is a relay of the person's own rather than Aster Relay, which is not open yet. */
    fun selfHosted(url: String?): Boolean {
        val host = url?.let(Lan::host)?.lowercase() ?: return false
        return host != Lan.host(DEFAULT_URL)
    }

    /** The connector's own rule: https anywhere, plain http only on this phone or the emulator's host. */
    fun usable(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host in LOCAL
            else -> false
        }
    }

    private val LOCAL = setOf("localhost", "127.0.0.1", "10.0.2.2")

    private const val SOCKET = "aster-relay"
    /** The connector waits 15 s on the relay itself; a little longer here so its own answer arrives. */
    private const val WAIT_MS = 20_000
}
