package inflation.ecology

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How soon the hazard rules kill unprepared people, against the record's deadlines (the skills floor's, from the
 * rules alone; the HexSkillsFloor probe and [inflation.run.Nominal.floor] check a whole world). Weeks are the model's
 * 6-day steps. Bands agreed by Olli, 2026-11-06.
 */
class UnpreparedDeadlinesTest {
    private val people = Catalogue.HUMAN
    private val days = 6

    /**
     * The share of people alive after each week of a total fast that starts with [fat] days of fat, by the hunger rule
     * ([Hazards.hunger] on the fat drawn as [Hazards.onFat] draws it, the body's protein burnt as [Hazards.spentAfter]
     * burns it). Index 0 the start.
     */
    private fun fast(fat: Float, weeks: Int = 16): DoubleArray {
        val alive = DoubleArray(weeks + 1).also { it[0] = 1.0 }
        var f = fat; var spent = 0f
        for (w in 1..weeks) {
            val fed = Hazards.onFat(0f, f, days)
            alive[w] = alive[w - 1] * (1f - Hazards.hunger(people, spent, fed, days))
            spent = Hazards.spentAfter(people, spent, fed, days)
            f = max(0f, f - min(f, days.toFloat()))
        }
        return alive
    }

    private fun halfDeadWeek(alive: DoubleArray) = (1 until alive.size).first { alive[it] < 0.5 }

    @Test
    fun `a total fast from full fat kills no one in four weeks and half in weeks 6 to 12`() {
        // (Record, recalled: well-nourished adults on water alone die after some 45-75 days, hunger strikers among them.
        // The rule's fast: first deaths in week 5, half in week 7, so at the short end; the band holds it there or later.)
        val alive = fast(people.maxFatDays)
        assertEquals(1.0, alive[4], 1e-9, "deaths before day 24: ${alive.toList()}")
        assertTrue(halfDeadWeek(alive) in 6..12, "half dead in week ${halfDeadWeek(alive)}: ${alive.toList()}")
    }

    @Test
    fun `a total fast with no fat left kills half in weeks 2 to 6`() {
        // (Record: starvation of the unprepared in 3 to 8 weeks, as neolithic-path.md recalls it; the lean go first.)
        val alive = fast(0f)
        assertTrue(halfDeadWeek(alive) in 2..6, "half dead in week ${halfDeadWeek(alive)}: ${alive.toList()}")
    }

    @Test
    fun `the unclad without fire or shelter die within a week of frost, and none in hot weather`() {
        // (Record: cold and wet kill the unclothed in hours to a night.)
        val naked = Cover(0f, 0f, 0f, 0f, 0f)
        val frost = BodyHeat.week(Air(-5f, 8f, 3f, 0f, false), naked, days).odds
        assertTrue(frost >= 0.5f, "a week at -5 °C unclad: $frost")
        assertEquals(0f, BodyHeat.week(Air(30f, 8f, 2f, 0f, false), naked, days).odds, "a week at 30 °C unclad")
    }

    @Test
    fun `the unclad without fire or shelter live through a dry week at 15 degrees and die within a week at 10`() {
        // (Record, recalled: no one dies of a dry night at 15 °C unclad; near 10 °C the unclad without fire die within days.)
        val naked = Cover(0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, BodyHeat.week(Air(15f, 8f, 1f, 0f, false), naked, days).odds, "a dry week at a mean of 15 °C unclad")
        val ten = BodyHeat.week(Air(10f, 8f, 1f, 0f, false), naked, days).odds
        assertTrue(ten >= 0.5f, "a dry week at a mean of 10 °C unclad: $ten")
    }
}
