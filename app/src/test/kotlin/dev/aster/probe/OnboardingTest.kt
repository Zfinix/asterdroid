package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {

    private fun code(request: String) = Onboarding.OpenRouter.callbackCode(request)

    @Test
    fun `the callback hands back its decoded code`() {
        assertEquals("abc/def", code("GET /callback?state=x&code=abc%2Fdef HTTP/1.1"))
    }

    @Test
    fun `a malformed escape is no code rather than a crash`() {
        assertNull(code("GET /callback?code=%zz HTTP/1.1"))
        assertNull(code("GET /callback?code=% HTTP/1.1"))
    }

    @Test
    fun `anything but the callback is no code`() {
        assertNull(code("GET /favicon.ico HTTP/1.1"))
        assertNull(code("GET /callback?error=denied HTTP/1.1"))
        assertNull(code(""))
    }
}
