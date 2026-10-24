package dev.aster.probe

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTest {

    @Test
    fun `private ranges are private and nothing else is`() {
        val private = listOf(
            "10.0.0.1", "10.255.255.255", "172.16.5.4", "172.31.255.255", "192.168.1.20", "127.0.0.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "fd00::1", "fc00::1", "fe80::1",
            "[fd12::3]", "localhost", "LOCALHOST", "mac.local",
        )
        val public = listOf(
            "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.255.255", "192.169.0.1", "11.0.0.1",
            "2001:db8::1", "example.com", "192.168.1.20.nip.io", "0x7f000001", "2130706433", "1.2.3", "",
            ".local", "local", "256.1.1.1", "01234.1.1.1",
        )
        private.forEach { assertTrue("$it should be private", Lan.isPrivateHost(it)) }
        public.forEach { assertFalse("$it should not be private", Lan.isPrivateHost(it)) }
    }

    @Test
    fun `loopback is only the phone itself`() {
        assertTrue(Lan.isLoopbackHost("127.0.0.1"))
        assertTrue(Lan.isLoopbackHost("127.1.2.3"))
        assertTrue(Lan.isLoopbackHost("localhost"))
        assertTrue(Lan.isLoopbackHost("[::1]"))
        assertFalse(Lan.isLoopbackHost("192.168.1.2"))
        assertFalse(Lan.isLoopbackHost("example.com"))
    }

    @Test
    fun `keys go over https anywhere or plain http to a private address`() {
        assertTrue(Lan.allowedUrl("https://api.example.com/v1"))
        assertTrue(Lan.allowedUrl("https://ai-gateway.kilo.ai/api/v1"))
        assertTrue(Lan.allowedUrl("http://192.168.1.20:11434/v1"))
        assertTrue(Lan.allowedUrl("http://[fd00::1]:1234/v1"))
        assertTrue(Lan.allowedUrl("http://localhost:1234/v1"))
        assertTrue(Lan.allowedUrl("  https://api.example.com/v1  "))
        assertFalse(Lan.allowedUrl("http://example.com/v1"))
        assertFalse(Lan.allowedUrl("http://8.8.8.8/v1"))
        assertFalse(Lan.allowedUrl("http://192.168.1.20.nip.io/v1"))
        assertFalse(Lan.allowedUrl("ftp://192.168.1.2/"))
        assertFalse(Lan.allowedUrl("https://user:pass@evil.example/v1"))
        assertFalse(Lan.allowedUrl("https://exa mple.com"))
        assertFalse(Lan.allowedUrl("https://a.example/v1\nASTER_AGENT_CMD=x"))
        assertFalse(Lan.allowedUrl("javascript:alert(1)"))
        assertFalse(Lan.allowedUrl("https://"))
        assertFalse(Lan.allowedUrl(""))
    }

    @Test
    fun `a sweep covers the slash 24 around the phone and skips the phone`() {
        val hosts = Lan.hosts("192.168.1.20", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.20" in hosts)
        assertEquals(253, Lan.hosts("10.1.2.3", 16).size)
        assertTrue(Lan.hosts("10.1.2.3", 16).all { it.startsWith("10.1.2.") })
        assertEquals(listOf("10.0.0.2"), Lan.hosts("10.0.0.1", 30))
        assertTrue(Lan.hosts("not an address", 24).isEmpty())
    }

    @Test
    fun `model ids come from an openai style list`() {
        assertEquals(listOf("qwen3.8:27b", "gemma4:12b"), Lan.modelIds("""{"data":[{"id":"qwen3.8:27b"},{"id":"gemma4:12b"},{"id":""}]}"""))
        assertNull(Lan.modelIds("<html>not json</html>"))
        assertNull(Lan.modelIds("""{"models":[]}"""))
    }

    @Test
    fun `get refuses cleartext to anywhere public before connecting`() {
        assertNull(Lan.get("http://8.8.8.8/v1/models", timeoutMs = 200))
        assertNull(Lan.get("https://192.168.1.20/v1/models", timeoutMs = 200))
        assertNull(Lan.get("http://example.com/v1/models", timeoutMs = 200))
    }

    @Test
    fun `get reads sized and chunked bodies from a private server`() {
        val sized = serve("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"data\":[]}")
        assertEquals(Lan.Response(200, "{\"data\":[]}"), Lan.get("http://127.0.0.1:${sized.first}/v1/models", mapOf("Authorization" to "Bearer local")))
        assertTrue(sized.second().contains("Authorization: Bearer local"))
        assertTrue(sized.second().startsWith("GET /v1/models HTTP/1.1"))

        val chunked = serve("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"da\r\n7\r\nta\":[]}\r\n0\r\n\r\n")
        assertEquals(Lan.Response(200, "{\"data\":[]}"), Lan.get("http://127.0.0.1:${chunked.first}/v1/models"))

        val refused = serve("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        assertEquals(401, Lan.get("http://127.0.0.1:${refused.first}/v1/models")?.code)
    }

    @Test
    fun `a header with a line break is not sent`() {
        val server = serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")
        Lan.get("http://127.0.0.1:${server.first}/", mapOf("X-Evil" to "a\r\nHost: other"))
        assertFalse(server.second().contains("X-Evil"))
    }

    /** One canned reply on a loopback port; the second value is what the client sent. */
    private fun serve(reply: String): Pair<Int, () -> String> {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var request = ""
        val done = thread {
            socket.use { server ->
                server.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    request = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.joinToString("\n")
                    client.getOutputStream().write(reply.toByteArray())
                }
            }
        }
        return socket.localPort to {
            done.join(2_000)
            request
        }
    }
}
