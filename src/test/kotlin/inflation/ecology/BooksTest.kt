package inflation.ecology

import inflation.Books
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BooksTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `water and matter close in every part of the step, but for the known leaks`() {
        val sim = HexSim(World.generate(cfg))
        val books = Books()
        sim.books = books
        val steps = 26
        repeat(steps) { sim.step() }
        // (Float stocks leave a little over: m³ of water against some 1e8 m³ a step, and tenths of a tonne of matter
        // against the plants' 1e6 t a step grown, fallen and burnt, one way or the other as the run goes: a millionth,
        // some sixteen float steps (6e-8) of it. So a part may leave over the larger of a floor and a millionth of what it
        // booked. A process that makes or loses matter unbooked leaves far more.)
        val most = mapOf(Books.Stuff.WATER to 100.0, Books.Stuff.MATTER to 300.0) // m³ and kg a step
        val off = books.partNames.flatMap { part ->
            Books.Stuff.entries.mapNotNull { s -> val x = books.leftOver(part, s) / steps
                val limit = max(most.getValue(s), FLOAT_SHARE * books.turnover(part, s) / steps)
                if (abs(x) > limit) "$part: ${x * s.scale} ${s.unit}/step (limit ${limit * s.scale})" else null }
        }
        assertTrue(off.isEmpty(), "left over beyond float noise:\n" + off.joinToString("\n") + "\n" + books.report(steps))
        assertTrue("water" in books.partNames && "groups: births, deaths" in books.partNames, "parts checked: ${books.partNames}")
        // (Each part of the week is checked under its own name.)
        val names = sim.phases.map { it.name }
        assertEquals(names.distinct(), names, "phases sharing a name")
        assertTrue(books.partNames.containsAll(names), "phases not checked: ${names - books.partNames.toSet()}")
    }

    private companion object {
        /** What float stocks may leave over of the flows a part booked: a millionth. */
        const val FLOAT_SHARE = 1e-6
    }
}
