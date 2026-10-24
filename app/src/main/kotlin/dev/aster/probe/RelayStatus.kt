package dev.aster.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * What the relay connector last said about itself, for the screens to draw.
 * `asterctl relay` reports on stdout, one line per change: `relay: online
 * <phoneId>`, `relay: offline <reason>`, and `relay: approve <pendingId> <json>`
 * when a new client asks in. Everything here is snapshot state, set on the main
 * thread, so a composable that reads it redraws when it changes.
 */
object RelayStatus {

    /** A client waiting for the person to let it in. [info] is the connector's JSON as sent. */
    data class Pending(val id: String, val name: String, val kind: String, val info: JSONObject)

    /** The connector process is up, whether or not it reached the relay. */
    var running by mutableStateOf(false)
        private set

    var online by mutableStateOf(false)
        private set

    var phoneId by mutableStateOf<String?>(null)
        private set

    /** Why it is offline, in the connector's words, or null while online. */
    var reason by mutableStateOf<String?>(null)
        private set

    val pending = mutableStateListOf<Pending>()

    private val main = Handler(Looper.getMainLooper())

    fun started() = main.post { running = true }

    /** The connector exited: nothing is online and nobody is still waiting on an answer from it. */
    fun stopped(why: String?) = main.post {
        running = false
        online = false
        reason = why
        pending.clear()
    }

    /**
     * One stdout line from the connector. Returns the status note for it, or
     * null when the line is not one of its reports.
     */
    fun line(text: String): String? {
        val words = text.trim().split(' ', limit = 3)
        if (words.size < 2 || words[0] != PREFIX) return null
        val rest = words.getOrNull(2).orEmpty()
        return when (words[1]) {
            "online" -> {
                main.post {
                    online = true
                    phoneId = rest.ifEmpty { null }
                    reason = null
                }
                "Online through Aster Relay"
            }
            "offline" -> {
                main.post {
                    online = false
                    reason = rest.ifEmpty { null }
                }
                if (rest.isEmpty()) "Offline from Aster Relay" else "Offline from Aster Relay: $rest"
            }
            "approve" -> {
                val parts = rest.split(' ', limit = 2)
                val id = parts.getOrNull(0).orEmpty()
                if (id.isEmpty()) return null
                val info = runCatching { JSONObject(parts.getOrNull(1).orEmpty()) }.getOrDefault(JSONObject())
                val ask = Pending(
                    id = id,
                    name = info.optString("name").ifEmpty { "A new client" },
                    kind = info.optString("kind"),
                    info = info,
                )
                main.post { if (pending.none { it.id == id }) pending.add(ask) }
                "${ask.name} wants to reach this phone. Open Droid to let it in."
            }
            else -> null
        }
    }

    /**
     * Let a waiting client in, or turn it away, through `asterctl relay approve`.
     * Off the main thread because it talks to the connector over its socket.
     */
    fun answer(context: Context, pendingId: String, yes: Boolean) {
        main.post { pending.removeAll { it.id == pendingId } }
        val client = File(context.applicationInfo.nativeLibraryDir, "libclient.so").absolutePath
        val home = context.filesDir
        thread(name = "aster-relay-approve") {
            runCatching {
                val process = ProcessBuilder(client, "relay", "approve", pendingId, if (yes) "yes" else "no")
                    .directory(home)
                    .redirectErrorStream(true)
                    .apply { environment()["HOME"] = home.absolutePath }
                    .start()
                // The reply is one short line, so it cannot fill the pipe while this waits.
                if (!process.waitFor(ANSWER_WAIT_S, TimeUnit.SECONDS)) {
                    process.destroy()
                    ActivityLog.state(context, "The relay did not take the answer in time.")
                    return@runCatching
                }
                val said = process.inputStream.bufferedReader().readText().trim()
                if (process.exitValue() != 0) ActivityLog.state(context, "The relay did not take the answer: ${said.take(NOTE_CAP)}")
            }.onFailure { Log.w(TAG, "approve failed: $it") }
        }
    }

    private const val PREFIX = "relay:"
    private const val ANSWER_WAIT_S = 10L
    private const val NOTE_CAP = 80
    private const val TAG = "ASTERRELAY"
}
