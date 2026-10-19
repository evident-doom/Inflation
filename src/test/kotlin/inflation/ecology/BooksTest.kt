package inflation.ecology

import inflation.Books
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.abs
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
        // against 1e5 t of litterfall; a process that makes or loses matter unbooked leaves far more. The plants' float
        // noise alone comes to about 300 kg a step either way on some seeds: -275 on seed 4, -315 on seed 3 once residents
        // flow towards better.)
        val most = mapOf(Books.Stuff.WATER to 100.0, Books.Stuff.MATTER to 500.0)
        val off = books.partNames.flatMap { part ->
            Books.Stuff.entries.mapNotNull { s -> val x = books.leftOver(part, s) / steps; if (abs(x) > most.getValue(s)) "$part: $x ${s.unit}/step" else null }
        }
        assertTrue(off.isEmpty(), "left over beyond float noise:\n" + off.joinToString("\n") + "\n" + books.report(steps))
        assertTrue("water" in books.partNames && "groups: births, deaths" in books.partNames, "parts checked: ${books.partNames}")
        // (Each part of the week is checked under its own name.)
        val names = sim.phases.map { it.name }
        assertEquals(names.distinct(), names, "phases sharing a name")
        assertTrue(books.partNames.containsAll(names), "phases not checked: ${names - books.partNames.toSet()}")
    }
}
