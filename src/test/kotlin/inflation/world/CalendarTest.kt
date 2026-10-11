package inflation.world

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarTest {
    @Test
    fun `year has twelve thirty-day months`() {
        val d = Date(360L * 3 + 30 * 4 + 9)
        assertEquals(3, d.year)
        assertEquals(4, d.month)
        assertEquals(10, d.dayOfMonth)
        assertEquals(Season.SUMMER, Date(100).season)
        assertEquals(Season.WINTER, Date(300).season)
    }

    @Test
    fun `moon is new on the first and full mid-month`() {
        assertEquals(MoonPhase.NEW, Date(0).moonPhase)
        assertEquals(MoonPhase.FULL, Date(15).moonPhase)
        assertTrue(Date(15).moonlight > 0.99)
        assertTrue(Date(0).moonlight < 0.01)
    }

    @Test
    fun `day length follows latitude and season`() {
        assertTrue(abs(Date(0).dayLengthHours(40.0) - 12.0) < 0.05, "equinox is 12 h")
        val summer = Date(90).dayLengthHours(40.0)
        val winter = Date(270).dayLengthHours(40.0)
        assertTrue(summer in 14.8..15.2, "40°N midsummer ~15 h, was $summer")
        assertTrue(winter in 8.8..9.4, "40°N midwinter ~9.2 h, was $winter")
        assertTrue(Date(90).dayLengthHours(31.0) < summer, "smaller seasonal swing further south")
    }
}
