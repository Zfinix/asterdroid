package dev.aster.probe

import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * The Agent Client Protocol over one `aster acp` child's stdio: JSON-RPC, one
 * message per line each way. This is the same surface the Telegram bridge and
 * the editors speak, so the in-app chat gets streaming text, tool calls, plans
 * and approvals without a protocol of its own.
 *
 * Stdout carries only protocol and stderr only the agent's tracing, so they are
 * read apart; merged, the first log line would be taken for a broken message.
 */
class AcpClient(private val process: Process, private val listener: Listener) {

    /** Called on the reader thread, in the order the agent wrote them. */
    interface Listener {
        fun onUpdate(params: JSONObject)
        fun onPermission(id: Any, params: JSONObject)
        fun onClosed(why: String)
    }

    /** A JSON-RPC error, kept whole because -32000 means the agent has no key. */
    class Failure(message: String, val code: Int) : Exception(message)

    private val writer = process.outputStream.bufferedWriter()
    private val next = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()

    @Volatile var alive = true
        private set

    /** The agent's last words on stderr, which is where it says why it would not start. */
    @Volatile var lastError = ""
        private set

    init {
        thread(name = "aster-acp-err") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, line.take(LOG_CAP))
                    if (line.isNotBlank()) lastError = line.trim().take(LOG_CAP)
                }
            }
        }
        thread(name = "aster-acp-out") { read() }
    }

    /** One request, waited for. Throws [Failure] for an error reply or a child that went away. */
    fun call(method: String, params: JSONObject): JSONObject {
        val id = next.getAndIncrement()
        val answer = CompletableFuture<JSONObject>()
        pending[id] = answer
        if (!alive) answer.completeExceptionally(Failure(GONE, GONE_CODE))
        write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        return try {
            answer.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JSONObject) =
        write(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    fun respond(id: Any, result: JSONObject) =
        write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))

    fun close() {
        alive = false
        runCatching { writer.close() }
        process.destroy()
    }

    /** A write to a child that is gone is the same as the reply never coming; the reader says so. */
    private fun write(message: JSONObject) = synchronized(writer) {
        runCatching {
            writer.write(message.toString())
            writer.write("\n")
            writer.flush()
        }.onFailure { Log.w(TAG, "write failed: $it") }
    }

    private fun read() {
        runCatching {
            process.inputStream.bufferedReader().forEachLine { line ->
                val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                route(message)
            }
        }
        alive = false
        val why = lastError.ifEmpty { GONE }
        pending.values.forEach { it.completeExceptionally(Failure(why, GONE_CODE)) }
        listener.onClosed(why)
    }

    /** Replies settle their waiter; what the agent asks or announces goes to the listener. */
    private fun route(message: JSONObject) {
        val method = message.optString("method").ifEmpty { null }
        val id = message.opt("id")?.takeIf { it != JSONObject.NULL }
        val params = message.optJSONObject("params") ?: JSONObject()
        when {
            id != null && method == PERMISSION -> listener.onPermission(id, params)
            id != null && method != null -> write(
                JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                    "error",
                    JSONObject().put("code", NOT_FOUND).put("message", "$method is not supported"),
                ),
            )
            id != null -> {
                val waiter = (id as? Number)?.toLong()?.let { pending[it] } ?: return
                val error = message.optJSONObject("error")
                if (error != null) {
                    waiter.completeExceptionally(
                        Failure(error.optString("message", "request failed"), error.optInt("code")),
                    )
                } else {
                    waiter.complete(message.optJSONObject("result") ?: JSONObject())
                }
            }
            method == UPDATE -> listener.onUpdate(params)
        }
    }

    companion object {
        /** The agent's code for a missing key or sign-in. */
        const val NO_CREDENTIALS = -32000
        private const val GONE_CODE = -1
        private const val NOT_FOUND = -32601
        private const val GONE = "The agent stopped."
        private const val UPDATE = "session/update"
        private const val PERMISSION = "session/request_permission"
        private const val LOG_CAP = 400
        private const val TAG = "ASTERACP"
    }
}
