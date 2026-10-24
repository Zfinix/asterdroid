package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessTreeTest {

    @Test
    fun `every descendant of the shell, and nothing beside it`() {
        val parents = mapOf(
            100 to 1,
            200 to 100,
            300 to 200,
            301 to 200,
            400 to 300,
            500 to 100,
            900 to 1,
        )
        assertEquals(setOf(300, 301, 400), ProcessTree.below(200, parents).toSet())
        assertEquals(emptyList<Int>(), ProcessTree.below(400, parents))
    }

    @Test
    fun `the parent is read past a name holding spaces and parentheses`() {
        assertEquals(4321, ProcessTree.parentOf("1234 (node) S 4321 1234 1234 0 -1"))
        assertEquals(77, ProcessTree.parentOf("88 (a (b) c) R 77 88 88 0 -1"))
        assertNull(ProcessTree.parentOf("garbage"))
    }
}
