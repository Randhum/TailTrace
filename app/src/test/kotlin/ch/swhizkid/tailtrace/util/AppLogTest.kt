package ch.swhizkid.tailtrace.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLogTest {

    @Before
    fun reset() {
        AppLog.clear()
    }

    @Test
    fun retainsOrderAndLevels() {
        AppLog.d("T", "debug")
        AppLog.i("T", "info")
        AppLog.w("T", "warn")
        AppLog.e("T", "error")
        val lines = AppLog.lines.value
        assertEquals(4, lines.size)
        assertEquals(
            listOf(AppLog.Level.D, AppLog.Level.I, AppLog.Level.W, AppLog.Level.E),
            lines.map { it.level }
        )
        assertEquals("warn", lines[2].message)
    }

    @Test
    fun clearEmptiesBuffer() {
        AppLog.i("T", "x")
        AppLog.clear()
        assertTrue(AppLog.lines.value.isEmpty())
    }

    @Test
    fun capsAtFiveHundred() {
        repeat(520) { AppLog.i("T", "line-$it") }
        val lines = AppLog.lines.value
        assertEquals(500, lines.size)
        assertEquals("line-20", lines.first().message)
        assertEquals("line-519", lines.last().message)
    }
}
