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
    fun `a band gives of what it holds beyond its year's keep as far as the other lacks it, grinding stone only to grinders`() {
        val rich = Band(1); val poor = Band(2)
        rich.goods[Material.SALT] = 100f
        // (10 grown keep 40 kg of salt a year: 60 to spare; 5 grown lack 20.)
        assertEquals(60f, spareGoods(rich, 10, Material.SALT), 1e-4f)
        assertEquals(20f, lackGoods(poor, 5, Material.SALT), 1e-4f)
        assertEquals(20f, goodsGiven(rich, 10, poor, 5, Material.SALT), 1e-4f)
        assertEquals(0f, goodsGiven(poor, 5, rich, 10, Material.SALT))
        rich.goods[Material.GROUND_STONE] = 1000f
        assertEquals(0f, goodsGiven(rich, 10, poor, 5, Material.GROUND_STONE), "a band that makes no grinding stones asks for none")
        poor.knows[Tool.GRINDER] = true
        assertTrue(goodsGiven(rich, 10, poor, 5, Material.GROUND_STONE) > 0f)
    }

    @Test
    fun `a band takes in a stock to give on beyond its keep, and spares it, but no grinding stone`() {
        val b = Band(1)
        // (5 grown keep 20 kg of salt a year; with a year's stock to give on it takes in 40.)
        assertEquals(40f, lackGoods(b, 5, Material.SALT, passOn = 1f), 1e-4f)
        b.goods[Material.SALT] = 40f
        assertEquals(20f, spareGoods(b, 5, Material.SALT), 1e-4f, "the stock is what it can give on")
        b.knows[Tool.GRINDER] = true
        assertEquals(lackGoods(b, 5, Material.GROUND_STONE), lackGoods(b, 5, Material.GROUND_STONE, passOn = 1f), "grinding stone is not given on")
    }

    @Test
    fun `carriers take a load each, one at least and all the grown at most`() {
        assertEquals(1f, carriersFor(3f, 10))
        assertEquals(3f, carriersFor(2.5f * FETCH_LOAD, 10))
        assertEquals(4f, carriersFor(100f * FETCH_LOAD, 4))
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
