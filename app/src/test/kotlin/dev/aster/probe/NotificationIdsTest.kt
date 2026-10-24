package dev.aster.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `no two notifications share an id`() {
        assertEquals(NotificationIds.all.size, NotificationIds.all.toSet().size)
    }
}
