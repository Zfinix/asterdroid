package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvTest {

    @Test
    fun `no child inherits what tells the linker what to load`() {
        val values = mapOf(
            "LD_PRELOAD" to "/sdcard/evil.so",
            "ld_library_path" to "/sdcard",
            "DYLD_INSERT_LIBRARIES" to "/sdcard/evil.dylib",
            "OPENROUTER_API_KEY" to "sk-or-1",
            Env.TELEGRAM_TOKEN to "123:abc",
        )
        assertEquals(
            mapOf("OPENROUTER_API_KEY" to "sk-or-1", Env.TELEGRAM_TOKEN to "123:abc"),
            Env.inheritable(values),
        )
    }

    @Test
    fun `a pushed env is only read where other apps cannot write it`() {
        assertFalse(Install.importsEnv(26))
        assertFalse(Install.importsEnv(29))
        assertTrue(Install.importsEnv(30))
        assertTrue(Install.importsEnv(35))
    }
}
