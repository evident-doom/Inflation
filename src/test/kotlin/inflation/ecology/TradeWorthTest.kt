package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What goods are worth to a band in days ([fetchDaysPerKg]), and the favours owed for them ([favoursOfDays]). */
class TradeWorthTest {
    @Test
    fun `at the source a kg costs only its work, further off the walk there and back with a load, never beyond the furthest reckoned`() {
        assertEquals(1f / SALT_A_DAY, fetchDaysPerKg(0f, SALT_A_DAY), 1e-6f)
        val far = fetchDaysPerKg(200f, SALT_A_DAY)
        assertEquals(2f * 200f / DAY_WALK_KM / FETCH_LOAD + 1f / SALT_A_DAY, far, 1e-5f)
        assertTrue(far > fetchDaysPerKg(100f, SALT_A_DAY))
        assertEquals(fetchDaysPerKg(UNKNOWN_SOURCE_KM, SALT_A_DAY), fetchDaysPerKg(5000f, SALT_A_DAY))
    }

    @Test
    fun `days spared make favours by what the band's hands would have foraged in them, a favour a week of its need`() {
        // (A day of all 10 hands brings 3 days of need: 10 person-days spared are 3 days of need.)
        assertEquals(3f / FAVOUR_DAYS, favoursOfDays(10f, 3f, adults = 10, size = 25), 1e-6f)
        // (Without a yield of late, a person-day for a person's day of need.)
        assertEquals(25f / 25f / FAVOUR_DAYS, favoursOfDays(25f, Float.NaN, adults = 10, size = 25), 1e-6f)
        assertEquals(0f, favoursOfDays(0f, 3f, 10, 25))
    }
}
