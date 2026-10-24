package dev.aster.probe

import android.content.Context
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * The first run: a brain, a place to talk to it, and the switches it needs,
 * in that order and one at a time. It shows until it is finished, so a phone
 * nobody has set up never opens on a home screen full of off switches, and it
 * can be run again from settings.
 */
object Onboarding {

    /** The steps, in the order they are walked. */
    enum class Step { WELCOME, BRAIN, TALK, PERMISSIONS, DONE }

    fun done(context: Context): Boolean = prefs(context).getBoolean(DONE, false)

    fun finish(context: Context) {
        prefs(context).edit().putBoolean(DONE, true).remove(STEP).apply()
    }

    /**
     * Where a run that has not finished stands, so a phone that restarts the
     * app halfway, which Android does while Settings is open, comes back to
     * the same step. Null when onboarding never started.
     */
    fun step(context: Context): Step? =
        prefs(context).getString(STEP, null)?.let { name -> Step.entries.firstOrNull { it.name == name } }

    fun saveStep(context: Context, step: Step) {
        prefs(context).edit().putString(STEP, step.name).apply()
    }

    /**
     * A phone that already has a key or a bot token was set up before
     * onboarding existed, by hand or over adb; walking it through again would
     * only ask for what it has. Only asked before onboarding first starts,
     * since the brain step itself writes a key.
     */
    fun alreadySetUp(context: Context): Boolean {
        val values = Env.all(context)
        return Env.TELEGRAM_TOKEN in values || Models.BASE_URL in values ||
            Models.catalog(context).any { it.needsKey && it.keyVars.any(values::containsKey) }
    }

    /**
     * OpenRouter's sign-in, which hands back a real key with nothing to copy:
     * PKCE with a callback to this phone's own loopback, the way the CLI does
     * it. The browser is sent to [url]; [await] then blocks until OpenRouter
     * redirects back, and trades the code for a key. The verifier never leaves
     * the phone, so a code that some other app on the phone catches is no use
     * to it.
     */
    class OpenRouter private constructor(private val server: ServerSocket, private val verifier: String) : Closeable {

        val url: String = "https://openrouter.ai/auth?callback_url=" +
            URLEncoder.encode("http://localhost:${server.localPort}/callback", "UTF-8") +
            "&code_challenge=${challenge(verifier)}&code_challenge_method=S256"

        /** The key, or null when the person gave up or the exchange failed. Blocks for up to five minutes. */
        fun await(): String? {
            val code = code() ?: return null
            return runCatching {
                val connection = (URL(KEYS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = EXCHANGE_MS
                    readTimeout = EXCHANGE_MS
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject()
                    .put("code", code)
                    .put("code_verifier", verifier)
                    .put("code_challenge_method", "S256")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                if (connection.responseCode != 200) return null
                val reply = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(reply).optString("key").takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        /**
         * Wait for the browser to come back with `?code=`. Anything else that
         * knocks, a favicon request for one, gets a 404 and the wait goes on.
         */
        private fun code(): String? {
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val socket = try {
                    server.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    return null
                } catch (_: Exception) {
                    return null
                }
                socket.use {
                    it.soTimeout = READ_MS
                    val request = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                    val code = callbackCode(request)
                    val page = if (code != null) DONE_PAGE else ""
                    val status = if (code != null) "200 OK" else "404 Not Found"
                    runCatching {
                        it.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray(),
                        )
                    }
                    if (code != null) return code
                }
            }
            return null
        }

        override fun close() {
            runCatching { server.close() }
        }

        companion object {
            /** Listen on loopback only, on a port the system picks, before the browser is sent anywhere. */
            fun start(): OpenRouter {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                val verifier = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                return OpenRouter(server, verifier)
            }

            /** The `code` in a `GET /callback?code=` request line, or null for anything else, a malformed escape included. */
            fun callbackCode(request: String): String? = request.split(' ').getOrNull(1)
                ?.takeIf { path -> path.startsWith("/callback?") }
                ?.substringAfter('?')
                ?.split('&')
                ?.firstOrNull { part -> part.startsWith("code=") }
                ?.substringAfter('=')
                ?.let { raw -> runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() }

            private fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

            private const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
            private const val WAIT_MS = 5 * 60 * 1000L
            private const val READ_MS = 5_000
            private const val EXCHANGE_MS = 20_000
            private const val DONE_PAGE = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
                "<body style=\"font-family:sans-serif;background:#0a0a0a;color:#ededed;padding:32px\">" +
                "<h2>Back to Droid</h2><p>OpenRouter sent Droid a code, and Droid is trading it for your key. " +
                "Go back to Droid to carry on.</p></body>"
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    private const val DONE = "done"
    private const val STEP = "step"
}
