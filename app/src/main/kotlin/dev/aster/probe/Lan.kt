package dev.aster.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The network the phone is on: which addresses never leave it, and which
 * model servers answer there. A laptop running Ollama or LM Studio is the one
 * brain that costs nothing and has no rate limit, and nobody knows its
 * address off by heart.
 *
 * Plain HTTP is only ever spoken to a private address. The platform's own
 * stack refuses cleartext outright (network_security_config), and a range of
 * addresses cannot be written there, so the requests to a LAN server go
 * through [get] below, which checks the address itself.
 */
object Lan {

    /** The two servers people run on a laptop, on the ports they listen on out of the box. */
    enum class Kind(val label: String, val port: Int) {
        OLLAMA("Ollama", 11434),
        LM_STUDIO("LM Studio", 1234),
    }

    /** One server that answered, with the models it says it serves. */
    data class Server(val kind: Kind, val host: String, val models: List<String>) {
        val url: String get() = "http://${bracketed(host)}:${kind.port}/v1"
        val name: String get() = "${kind.label} on ${if (isLoopbackHost(host)) "this phone" else host}"
    }

    /** What a plain GET came back with. */
    data class Response(val code: Int, val body: String)

    /**
     * Addresses that stay on this network: RFC 1918, loopback, link-local,
     * carrier-grade NAT (where Tailscale lives), IPv6 unique-local, and mDNS
     * names. Only literals count. A name that merely resolves to a private
     * address today can resolve elsewhere tomorrow, so names are never looked up.
     */
    fun isPrivateHost(host: String): Boolean {
        val bare = host.trim().trim('[', ']').lowercase()
        if (bare.isEmpty()) return false
        if (bare == "localhost" || bare.endsWith(".local") && bare.length > ".local".length) return true
        ipv4(bare)?.let { (a, b) ->
            return a == 10 ||
                a == 127 ||
                a == 172 && b in 16..31 ||
                a == 192 && b == 168 ||
                a == 169 && b == 254 ||
                a == 100 && b in 64..127
        }
        val v6 = ipv6(bare) ?: return false
        val first = v6.address[0].toInt() and 0xff
        val second = v6.address[1].toInt() and 0xff
        return v6.isLoopbackAddress || first and 0xfe == 0xfc || first == 0xfe && second and 0xc0 == 0x80
    }

    /** The phone itself, however the address is spelt. */
    fun isLoopbackHost(host: String): Boolean {
        val bare = host.trim().trim('[', ']').lowercase()
        if (bare == "localhost") return true
        ipv4(bare)?.let { return it[0] == 127 }
        return ipv6(bare)?.isLoopbackAddress ?: false
    }

    /**
     * Whether requests, and the key that rides with them, may go to [url]:
     * any https address, or plain http to a private one. A link with a name
     * and password before the host is refused, because the part people read
     * first is then not where it goes.
     */
    fun allowedUrl(url: String): Boolean {
        val text = url.trim()
        if (text.isEmpty() || text.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() } ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isPrivateHost(host)
            else -> false
        }
    }

    /** The host of [url] without brackets, or null when it has none. */
    fun host(url: String): String? =
        runCatching { URI(url.trim()).host?.trim('[', ']') }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun port(url: String): Int? =
        runCatching { URI(url.trim()).port }.getOrNull()?.takeIf { it > 0 }

    /**
     * Every other address in the phone's subnet, capped at the /24 around it.
     * A /16 is 65,000 hosts, which takes minutes to sweep and is not where a
     * laptop on home Wi-Fi sits.
     */
    fun hosts(address: String, prefix: Int): List<String> {
        val parts = ipv4(address) ?: return emptyList()
        val bits = prefix.coerceIn(SMALLEST_SWEEP, 30)
        val own = parts.fold(0L) { acc, part -> acc shl 8 or part.toLong() }
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        val network = own and mask
        val size = 1L shl (32 - bits)
        return (1 until size - 1)
            .map { network + it }
            .filter { it != own }
            .map { n -> "${n shr 24 and 0xff}.${n shr 16 and 0xff}.${n shr 8 and 0xff}.${n and 0xff}" }
    }

    /**
     * The phone's IPv4 address and prefix on Wi-Fi or Ethernet, or null on
     * mobile data, where the neighbours are other people's phones.
     */
    fun address(context: Context): Pair<String, Int>? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        val link = manager.getLinkProperties(network)?.linkAddresses ?: return null
        return link.firstOrNull { it.address is Inet4Address }
            ?.let { it.address.hostAddress.orEmpty() to it.prefixLength }
            ?.takeIf { it.first.isNotEmpty() }
    }

    /**
     * Sweep the subnet for Ollama and LM Studio, plus this phone itself for a
     * server run in a terminal app or reached through `adb reverse`. A port
     * that opens is only counted once it lists models, so something else
     * listening on 1234 is not offered as a brain.
     */
    fun scan(context: Context): List<Server> {
        val own = ownAddresses()
        val targets = listOf(LOOPBACK) +
            (address(context)?.let { (ip, prefix) -> hosts(ip, prefix) }.orEmpty()).filterNot { it in own }
        val pool = Executors.newFixedThreadPool(PARALLEL)
        return try {
            val jobs = targets.flatMap { host -> Kind.entries.map { kind -> pool.submit<Server?> { probe(host, kind) } } }
            jobs.mapNotNull { job -> runCatching { job.get(SCAN_CEILING_MS, TimeUnit.MILLISECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Every address the phone itself holds, on any interface. A server on the
     * phone already shows once as "this phone"; finding it again on the
     * phone's Ethernet or hotspot address would offer it twice.
     */
    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { (it as? Inet4Address)?.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    /** Whether the phone is on Wi-Fi at all, which a sweep needs before it can find anything. */
    fun onWifi(context: Context): Boolean = address(context) != null

    private fun probe(host: String, kind: Kind): Server? {
        val open = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, kind.port), CONNECT_MS) }
        }.isSuccess
        if (!open) return null
        val reply = get("http://${bracketed(host)}:${kind.port}/v1/models", timeoutMs = LIST_MS) ?: return null
        if (reply.code != 200) return null
        return Server(kind, host, modelIds(reply.body) ?: return null)
    }

    /** The ids an OpenAI-style `/models` reply lists, or null when it is not one. */
    fun modelIds(body: String): List<String>? = runCatching {
        val rows = JSONObject(body).getJSONArray("data")
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
    }.getOrNull()

    /**
     * A GET over a bare socket, for plain http to a private address and
     * nothing else. HTTP/1.1 with `Connection: close`, reading a sized,
     * chunked, or until-close body, capped so a server that streams forever
     * cannot fill the phone's memory.
     */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = LIST_MS): Response? = runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.trim('[', ']') ?: return null
        if (uri.scheme?.lowercase() != "http" || !isPrivateHost(host)) return null
        val port = if (uri.port > 0) uri.port else 80
        val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: ${bracketed(host)}:$port\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                headers.forEach { (name, value) ->
                    if (name.none { it == '\r' || it == '\n' } && value.none { it == '\r' || it == '\n' }) {
                        append("$name: $value\r\n")
                    }
                }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = socket.getInputStream().buffered()
            val status = line(input) ?: return null
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
            var length = -1L
            var chunked = false
            while (true) {
                val header = line(input) ?: return null
                if (header.isEmpty()) break
                val name = header.substringBefore(':').trim().lowercase()
                val value = header.substringAfter(':', "").trim()
                if (name == "content-length") length = value.toLongOrNull() ?: -1L
                if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
            }
            val body = when {
                chunked -> chunks(input)
                length >= 0 -> read(input, length.coerceAtMost(MAX_BODY.toLong()))
                else -> read(input, MAX_BODY.toLong())
            }
            Response(code, String(body.toByteArray(), Charsets.UTF_8))
        }
    }.getOrNull()

    private fun chunks(input: InputStream): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        while (out.size() < MAX_BODY) {
            val size = line(input)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: break
            if (size <= 0) break
            out.write(read(input, size.coerceAtMost((MAX_BODY - out.size()).toLong())).toByteArray())
            line(input)
        }
        return out
    }

    private fun read(input: InputStream, limit: Long): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var left = limit
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        return out
    }

    /** One CRLF-ended header line, or null when the stream ends first or the line runs too long. */
    private fun line(input: InputStream): String? {
        val out = StringBuilder()
        while (out.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
        }
        return null
    }

    /** Four decimal parts of 0 to 255, or null. Other spellings, such as hex, are not taken as addresses. */
    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }.toIntArray()
    }

    /** An IPv6 literal, parsed without any lookup because it contains a colon. */
    private fun ipv6(host: String): Inet6Address? {
        if (':' !in host || host.any { !(it.isLetterOrDigit() || it == ':' || it == '.' || it == '%') }) return null
        return runCatching { InetAddress.getByName(host) as? Inet6Address }.getOrNull()
    }

    private fun bracketed(host: String) = if (':' in host) "[$host]" else host

    private const val LOOPBACK = "127.0.0.1"
    /** Wider than a /24 is cut down to the /24 around the phone. */
    private const val SMALLEST_SWEEP = 24
    private const val PARALLEL = 64
    private const val CONNECT_MS = 300
    private const val LIST_MS = 1_500
    private const val SCAN_CEILING_MS = 10_000L
    private const val MAX_BODY = 4 * 1024 * 1024
    private const val MAX_LINE = 8 * 1024
}
