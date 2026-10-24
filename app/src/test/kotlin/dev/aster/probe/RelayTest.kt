package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {

    @Test
    fun `only a relay of one's own counts until aster relay opens`() {
        assertFalse(Relay.selfHosted(null))
        assertFalse(Relay.selfHosted(""))
        assertFalse(Relay.selfHosted("https://relay.withaster.dev"))
        assertFalse(Relay.selfHosted("https://RELAY.withaster.dev/"))
        assertTrue(Relay.selfHosted("https://relay.example.org"))
        assertTrue(Relay.selfHosted("http://192.168.1.20:8787"))
    }

    @Test
    fun `only an address the connector will dial is usable`() {
        assertTrue(Relay.usable("https://relay.example.org"))
        assertTrue(Relay.usable("http://localhost:8787"))
        assertTrue(Relay.usable("http://10.0.2.2:8787"))
        assertFalse(Relay.usable("http://192.168.1.20:8787"))
        assertFalse(Relay.usable("ws://relay.example.org"))
        assertFalse(Relay.usable("not a url"))
    }

    @Test
    fun `a pairing code reads as the connector prints it`() {
        val code = Relay.code("""{"code":"7K2Q9MXD","exp":1761200300,"url":"https://relay.withaster.dev/pair#1.ph_x.7K2Q9MXD.ABCD"}""")
        assertEquals("7K2Q9MXD", code.code)
        assertEquals("7K2Q-9MXD", code.shown)
        assertEquals(1761200300L, code.expires)
        assertEquals("https://relay.withaster.dev/pair#1.ph_x.7K2Q9MXD.ABCD", code.url)
    }

    @Test
    fun `clients keep their scopes and times`() {
        val clients = Relay.clients(
            """[{"id":"c_1","name":"Claude Code on mbp","kind":"mcp-http","scopes":["phone:act","agent:chat"],"created":10,"last_seen":20},""" +
                """{"id":"c_2","name":"","kind":"browser","scopes":null,"created":null,"last_seen":null}]""",
        )
        assertEquals(2, clients.size)
        assertEquals(Relay.Client("c_1", "Claude Code on mbp", "mcp-http", listOf("phone:act", "agent:chat"), 10, 20), clients[0])
        assertEquals("A client", clients[1].name)
        assertEquals(emptyList<String>(), clients[1].scopes)
    }

    @Test
    fun `status survives a phone with no id yet`() {
        assertEquals(
            Relay.Status(online = false, phoneId = null, url = "https://relay.withaster.dev", entitled = false),
            Relay.status("""{"online":false,"phone_id":null,"url":"https://relay.withaster.dev","entitled":false}"""),
        )
        assertEquals("ph_abc", Relay.status("""{"online":true,"phone_id":"ph_abc","url":"u","entitled":true}""").phoneId)
    }

    @Test
    fun `an error reply is told apart from an answer`() {
        assertEquals("no client is waiting as c_9", Relay.error("""{"error":"no client is waiting as c_9"}"""))
        assertNull(Relay.error("""{"revoked":["c_1"]}"""))
        assertNull(Relay.error("""[{"id":"c_1"}]"""))
    }
}
