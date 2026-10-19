package inflation.run

import inflation.ecology.HexHistory
import inflation.ecology.HexHistory.Key
import inflation.ecology.HexSim
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/** The nominal limits ([Nominal]) pass a run that behaves and catch the faults they are for. */
class NominalTest {
    private val weeks = 3 * WorldConfig().stepsPerYear

    /** A history of three years: a steady kind, plants and the land, with [change] applied to its series. */
    private fun history(change: (Map<String, HexHistory.FloatList>) -> Unit = {}): HexHistory {
        val h = HexHistory()
        val series = mapOf(Key.animals("roe deer") to 1000f, Key.biomass("plants") to 5e6f, Key.LAND_AREA to 1e6f, Key.TEMPERATURE to 10f, Key.RAIN to 10f)
            .mapValues { (k, v) -> HexHistory.FloatList().also { s -> h.series[k] = s; repeat(weeks) { s.add(v) } } }
        repeat(weeks) { h.year.add((it / WorldConfig().stepsPerYear).toFloat()); h.month.add((it % 60 / 5).toFloat()) }
        change(series)
        return h
    }

    @Test
    fun `a steady run is within limits`() {
        val r = Nominal.history(history())
        assertTrue(r.ok, r.report())
    }

    @Test
    fun `a kind gone, matter below zero and a number that is not one are found`() {
        val r = Nominal.history(history { s ->
            s.getValue(Key.animals("roe deer")).add(0f)
            s.getValue(Key.biomass("plants")).add(-1f)
            s.getValue(Key.TEMPERATURE).add(Float.POSITIVE_INFINITY)
            for (k in listOf(Key.LAND_AREA, Key.RAIN)) s.getValue(k).add(s.getValue(k)[0])
        }.also { it.year.add(3f); it.month.add(0f) })
        val what = r.findings.map { it.what }.toSet()
        assertTrue(Key.animals("roe deer") in what && Key.biomass("plants") in what && Key.TEMPERATURE in what, r.report())
    }

    @Test
    fun `a kind falling tenfold in a year is found`() {
        val r = Nominal.history(history { s -> val a = s.getValue(Key.animals("roe deer")); for (i in 2 * WorldConfig().stepsPerYear until weeks) a.data[i] = 100f })
        assertTrue(r.findings.any { it.what == Key.animals("roe deer") }, r.report())
    }

    @Test
    fun `a small world's state after some weeks is within limits`() {
        val sim = HexSim(World.generate(WorldConfig(seed = 3, widthKm = 200, heightKm = 200, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)))
        repeat(10) { sim.step() }
        val r = Nominal.state(sim)
        assertTrue(r.ok, r.report())
    }
}
