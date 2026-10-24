package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private fun row(id: String, modalities: List<String>, tools: Boolean) =
        """{"id":"$id","architecture":{"input_modalities":[${modalities.joinToString(",") { "\"$it\"" }}]},""" +
            """"supported_parameters":[${if (tools) "\"tools\"" else "\"max_tokens\""}]}"""

    private fun list(vararg rows: String) = """{"data":[${rows.joinToString(",")}]}"""

    @Test
    fun `the house free model wins when it is listed`() {
        val body = list(
            row("kilo-auto/free", listOf("text"), tools = true),
            row("dots-studio/dots-3-note-preview:free", listOf("text", "image"), tools = true),
            row("nvidia/nemotron-3.5-super-vl:free", listOf("text", "image", "video"), tools = true),
            row("anthropic/claude-opus-5", listOf("text", "image"), tools = true),
        )
        assertEquals("nvidia/nemotron-3.5-super-vl:free", Models.pickFree(body))
    }

    @Test
    fun `without it any free model that sees and calls tools, never a router`() {
        val body = list(
            row("openrouter/free", listOf("text", "image"), tools = true),
            row("nvidia/nemotron-3.5-content-safety:free", listOf("text", "image"), tools = false),
            row("dots-studio/dots-3-note-preview:free", listOf("text", "image"), tools = true),
        )
        assertEquals("dots-studio/dots-3-note-preview:free", Models.pickFree(body))
    }

    @Test
    fun `text only free models are the last resort`() {
        val body = list(
            row("poolside/laguna-s-2.1:free", listOf("text"), tools = true),
            row("nvidia/nemotron-3-ultra-550b-a55b:free", listOf("text"), tools = true),
            row("paid/model", listOf("text", "image"), tools = true),
        )
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b:free", Models.pickFree(body))
        assertEquals("poolside/laguna-s-2.1:free", Models.pickFree(list(row("poolside/laguna-s-2.1:free", listOf("text"), tools = true))))
    }

    @Test
    fun `nothing free or nothing readable picks nothing`() {
        assertNull(Models.pickFree(list(row("paid/model", listOf("text", "image"), tools = true))))
        assertNull(Models.pickFree("not json"))
    }

    @Test
    fun `a localhost provider is local and a lan one is not`() {
        val local = Models.Provider("lmstudio", "LM Studio", "http://localhost:1234/v1", "m", emptyList(), emptyList(), false)
        val lan = local.copy(id = Models.CUSTOM, baseUrl = "http://192.168.1.20:1234/v1")
        assertTrue(local.local)
        assertFalse(lan.local)
        assertTrue(lan.custom)
    }

    @Test
    fun `custom values clear a stale key when none is given`() {
        assertEquals(
            mapOf("ASTER_BASE_URL" to "http://192.168.1.20:11434/v1", "ASTER_MODEL" to "qwen3.8:27b", "ASTER_API_KEY" to ""),
            Models.customValues(" http://192.168.1.20:11434/v1 ", "qwen3.8:27b", null),
        )
    }

    @Test
    fun `the shortlist starts with the free door`() {
        assertEquals(Models.FREE, Models.SHORTLIST.first())
        assertFalse("groq" in Models.SHORTLIST)
    }
}
