package dev.aster.probe

import java.util.Base64
import java.util.zip.Deflater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLinkTest {

    private val now = 1_761_200_000L

    private val openRouter = Models.Provider(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        model = "deepseek/deepseek-v4-pro",
        keyVars = listOf("OPEN_ROUTER_API_KEY", "OPENROUTER_API_KEY"),
        recommended = emptyList(),
        needsKey = true,
    )
    private val catalog = listOf(openRouter)

    private fun pack(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val n = deflater.deflate(buffer)
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(n))
    }

    private fun payload(edit: JSONObject.() -> Unit = {}): JSONObject = JSONObject()
        .put("v", 1)
        .put("exp", now + 600)
        .put("n", "q3JxAbCdEfGh1234")
        .apply(edit)

    private fun link(json: JSONObject, base: String = "https://droid.withaster.dev/s") = "$base#1.${pack(json.toString())}"

    private fun parse(json: JSONObject, seen: Set<String> = emptySet()) = SetupLink.parse(link(json), now, seen)

    private fun ok(json: JSONObject): SetupLink.Setup {
        val parsed = parse(json)
        assertTrue("expected Ok, got $parsed", parsed is SetupLink.Parsed.Ok)
        return (parsed as SetupLink.Parsed.Ok).setup
    }

    private fun bad(json: JSONObject): String {
        val parsed = parse(json)
        assertTrue("expected Bad, got $parsed", parsed is SetupLink.Parsed.Bad)
        return (parsed as SetupLink.Parsed.Bad).why
    }

    @Test
    fun `a full web link parses into every part`() {
        val setup = ok(
            payload {
                put(
                    "p",
                    JSONObject()
                        .put("id", "openrouter")
                        .put("url", "https://openrouter.ai/api/v1")
                        .put("m", "z-ai/glm-5.3-flash")
                        .put("kv", "OPENROUTER_API_KEY")
                        .put("k", "sk-or-v1-abcdef")
                        .put("e", "medium"),
                )
                put("ch", JSONObject().put("tg", JSONObject().put("t", "123456:AAbbccddeeffgghhiijjkkll").put("u", "98765")))
                put("mcp", JSONObject().put("on", true))
                put("relay", JSONObject().put("url", "https://relay.example.org"))
                put("claim", JSONObject().put("pub", "cHVi").put("nonce", "abcdefghijklmnop").put("name", "Chrome on mbp"))
            },
        )
        assertEquals("q3JxAbCdEfGh1234", setup.nonce)
        assertEquals("openrouter", setup.brain?.id)
        assertEquals("z-ai/glm-5.3-flash", setup.brain?.model)
        assertEquals("sk-or-v1-abcdef", setup.brain?.key)
        assertEquals("medium", setup.brain?.effort)
        assertEquals(SetupLink.Reach.Telegram("123456:AAbbccddeeffgghhiijjkkll", "98765"), setup.reach)
        assertEquals(true, setup.mcp)
        assertEquals("https://relay.example.org", setup.relayUrl)
        assertEquals("Chrome on mbp", setup.claim?.name)
        assertEquals(
            "https://relay.example.org/claim#1.cHVi.abcdefghijklmnop.Q2hyb21lIG9uIG1icA",
            setup.claim?.link("https://relay.example.org/"),
        )
    }

    @Test
    fun `the app scheme carries the same payload`() {
        val parsed = SetupLink.parse(link(payload(), "asterdroid://setup"), now, emptySet())
        assertTrue(parsed is SetupLink.Parsed.Ok)
    }

    @Test
    fun `other hosts and paths are not setup links`() {
        assertFalse(SetupLink.isSetupLink("https://evil.example/s#1.abc"))
        assertFalse(SetupLink.isSetupLink("https://droid.withaster.dev/setup#1.abc"))
        assertFalse(SetupLink.isSetupLink("http://droid.withaster.dev/s#1.abc"))
        assertFalse(SetupLink.isSetupLink("asterdroid://pair#1.abc"))
        assertTrue(SetupLink.isSetupLink("https://droid.withaster.dev/s#1.abc"))
        assertTrue(SetupLink.parse("https://evil.example/s#1.${pack(payload().toString())}", now, emptySet()) is SetupLink.Parsed.Bad)
    }

    @Test
    fun `expiry must be ahead and within fifteen minutes`() {
        assertTrue(bad(payload { put("exp", now - 1) }).contains("expired"))
        assertTrue(bad(payload { put("exp", now + 16 * 60 + 61) }).contains("15 minutes"))
        assertTrue(bad(payload { remove("exp") }).contains("no expiry"))
        ok(payload { put("exp", now + 15 * 60 + 30) })
    }

    @Test
    fun `a used nonce is refused`() {
        val parsed = parse(payload(), seen = setOf("q3JxAbCdEfGh1234"))
        assertTrue((parsed as SetupLink.Parsed.Bad).why.contains("already used"))
    }

    @Test
    fun `a newer version or a damaged payload is refused`() {
        assertTrue(bad(payload { put("v", 2) }).contains("newer"))
        assertTrue(SetupLink.parse("https://droid.withaster.dev/s#2.abc", now, emptySet()) is SetupLink.Parsed.Bad)
        assertTrue(SetupLink.parse("https://droid.withaster.dev/s#1.!!!", now, emptySet()) is SetupLink.Parsed.Bad)
        assertTrue(SetupLink.parse("https://droid.withaster.dev/s#1.${Base64.getUrlEncoder().encodeToString("nope".toByteArray())}", now, emptySet()) is SetupLink.Parsed.Bad)
        assertTrue(SetupLink.parse("https://droid.withaster.dev/s", now, emptySet()) is SetupLink.Parsed.Bad)
    }

    @Test
    fun `a payload that inflates past the cap is refused`() {
        val huge = payload { put("pad", "a".repeat(100_000)) }
        assertTrue(parse(huge) is SetupLink.Parsed.Bad)
    }

    @Test
    fun `plain http is only taken to a private address`() {
        bad(payload { put("p", JSONObject().put("id", "ollama").put("url", "http://example.com:11434/v1")) })
        bad(payload { put("relay", JSONObject().put("url", "http://relay.example.com")) })
        bad(payload { put("relay", JSONObject().put("url", "http://192.168.1.20:8787")) })
        val local = ok(payload { put("p", JSONObject().put("id", "ollama").put("url", "http://192.168.1.20:11434/v1").put("m", "qwen3.8:27b")) })
        assertEquals("http://192.168.1.20:11434/v1", local.brain?.url)
    }

    @Test
    fun `a line break in any value is refused`() {
        val why = bad(
            payload {
                put("p", JSONObject().put("id", "openrouter").put("k", "sk-or\nASTER_AGENT_CMD=sh -c evil"))
            },
        )
        assertTrue(why.contains("characters"))
        bad(payload { put("p", JSONObject().put("id", "openrouter").put("m", "model\nPATH=/x")) })
        bad(payload { put("claim", JSONObject().put("pub", "cHVi").put("nonce", "abcdefghijklmnop").put("name", "a\u0000b")) })
    }

    @Test
    fun `a link may not name a dangerous variable for its key`() {
        for (name in listOf("ASTER_AGENT_CMD", "LD_PRELOAD", "PATH", "ASTER_MCP_BIND", "ASTER_ACP_BIND", "HOME")) {
            bad(payload { put("p", JSONObject().put("id", "openrouter").put("kv", name).put("k", "x")) })
        }
        bad(payload { put("p", JSONObject().put("id", "openrouter").put("kv", "lower_case").put("k", "x")) })
    }

    @Test
    fun `forbidden covers every spelling of what runs and where it listens`() {
        assertTrue(SetupLink.forbidden("ASTER_AGENT_CMD"))
        assertTrue(SetupLink.forbidden("aster_agent_cmd"))
        assertTrue(SetupLink.forbidden("LD_LIBRARY_PATH"))
        assertTrue(SetupLink.forbidden("DYLD_INSERT_LIBRARIES"))
        assertTrue(SetupLink.forbidden("CLASSPATH"))
        assertTrue(SetupLink.forbidden("ASTER_MCP_BIND"))
        assertTrue(SetupLink.forbidden("SOMETHING_CMD"))
        assertTrue(SetupLink.forbidden("ASTER_BASE_URL"))
        assertFalse(SetupLink.forbidden("OPENROUTER_API_KEY"))
        assertFalse(SetupLink.forbidden("ASTER_API_KEY"))
    }

    @Test
    fun `a known provider's key goes under its own variable only`() {
        val setup = ok(payload { put("p", JSONObject().put("id", "openrouter").put("k", "sk-or-1").put("kv", "OPENROUTER_API_KEY")) })
        val values = SetupLink.values(setup, catalog, null) { "t".repeat(64) }
        assertEquals(mapOf("OPENROUTER_API_KEY" to "sk-or-1"), values)

        val defaulted = ok(payload { put("p", JSONObject().put("id", "openrouter").put("k", "sk-or-1")) })
        assertEquals(mapOf("OPEN_ROUTER_API_KEY" to "sk-or-1"), SetupLink.values(defaulted, catalog, null) { "" })

        val wrong = ok(payload { put("p", JSONObject().put("id", "openrouter").put("k", "sk-or-1").put("kv", "GROQ_API_KEY")) })
        assertNotNull(SetupLink.check(wrong, catalog))
    }

    @Test
    fun `an address the catalog does not know becomes the custom server`() {
        val setup = ok(
            payload {
                put("p", JSONObject().put("id", "openrouter").put("url", "https://evil.example/v1").put("m", "m1").put("k", "secret"))
            },
        )
        assertNull(SetupLink.known(setup.brain!!, catalog))
        assertEquals(
            mapOf("ASTER_BASE_URL" to "https://evil.example/v1", "ASTER_MODEL" to "m1", "ASTER_API_KEY" to "secret"),
            SetupLink.values(setup, catalog, null) { "" },
        )
        val named = ok(payload { put("p", JSONObject().put("id", "x").put("url", "https://a.example/v1").put("kv", "OPENAI_API_KEY").put("k", "k")) })
        assertNotNull(SetupLink.check(named, catalog))
        val nowhere = ok(payload { put("p", JSONObject().put("id", "unknown").put("k", "k")) })
        assertNotNull(SetupLink.check(nowhere, catalog))
    }

    @Test
    fun `channels, mcp and the relay land as their own names`() {
        val telegram = ok(payload { put("ch", JSONObject().put("tg", JSONObject().put("t", "123456:AAbbccddeeffgghhiijjkkll").put("u", "1, 2"))) })
        assertEquals(
            mapOf("ASTER_CHANNEL" to "telegram", "ASTER_TELEGRAM_TOKEN" to "123456:AAbbccddeeffgghhiijjkkll", "ASTER_REMOTE_USERS" to "1,2"),
            SetupLink.values(telegram, catalog, null) { "" },
        )
        val photon = ok(
            payload {
                put(
                    "ch",
                    JSONObject().put(
                        "photon",
                        JSONObject().put("secret", "pho_sk_abc").put("project", "pho_prj_def").put("senders", org.json.JSONArray().put("+447700900123")),
                    ),
                )
            },
        )
        assertEquals(
            mapOf(
                "ASTER_CHANNEL" to "photon",
                "ASTER_PHOTON_KEY" to "pho_sk_abc",
                "ASTER_PHOTON_PROJECT" to "pho_prj_def",
                "ASTER_REMOTE_USERS" to "+447700900123",
            ),
            SetupLink.values(photon, catalog, null) { "" },
        )
        val app = ok(payload { put("ch", JSONObject().put("app", true)) })
        assertEquals(mapOf("ASTER_CHANNEL" to "app"), SetupLink.values(app, catalog, null) { "" })

        val mcpOn = ok(payload { put("mcp", JSONObject().put("on", true)) })
        assertEquals("n".repeat(64), SetupLink.values(mcpOn, catalog, null) { "n".repeat(64) }["ASTER_MCP_TOKEN"])
        assertEquals("k".repeat(40), SetupLink.values(mcpOn, catalog, "k".repeat(40)) { "n".repeat(64) }["ASTER_MCP_TOKEN"])
        val mcpOff = ok(payload { put("mcp", JSONObject().put("on", false)) })
        assertEquals("", SetupLink.values(mcpOff, catalog, "k".repeat(40)) { "" }["ASTER_MCP_TOKEN"])

        val relay = ok(payload { put("relay", JSONObject().put("url", "https://relay.example.org")) })
        assertEquals(
            mapOf("ASTER_RELAY_URL" to "https://relay.example.org", "ASTER_RELAY" to "on"),
            SetupLink.values(relay, catalog, null) { "" },
        )
    }

    @Test
    fun `aster relay is coming soon, so a link cannot turn it on`() {
        val named = ok(payload { put("relay", JSONObject().put("url", "https://relay.withaster.dev")) })
        assertNull(named.relayUrl)
        assertEquals(emptyMap<String, String>(), SetupLink.values(named, catalog, null) { "" })

        val claim = JSONObject().put("pub", "cHVi").put("nonce", "abcdefghijklmnop").put("name", "Chrome on mbp")
        val pairing = ok(payload { put("claim", claim) })
        assertEquals(emptyMap<String, String>(), SetupLink.values(pairing, catalog, null) { "" })
        assertEquals(emptyMap<String, String>(), SetupLink.values(pairing, catalog, null, "https://relay.withaster.dev/") { "" })
        assertEquals(
            mapOf("ASTER_RELAY" to "on"),
            SetupLink.values(pairing, catalog, null, "https://relay.example.org") { "" },
        )
    }

    @Test
    fun `a telegram token that is not one is refused`() {
        bad(payload { put("ch", JSONObject().put("tg", JSONObject().put("t", "not a token"))) })
        bad(payload { put("ch", JSONObject().put("tg", JSONObject().put("t", "123456:AAbbccddeeffgghhiijjkkll").put("u", "1;rm -rf"))) })
    }

    @Test
    fun `tokens are 64 hex characters and never repeat`() {
        val a = SetupLink.token()
        val b = SetupLink.token()
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertNotEquals(a, b)
    }
}
