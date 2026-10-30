package inflation.run

import inflation.StockName
import inflation.ecology.ByBeast
import inflation.ecology.Catalogue
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

    /** A history of three years: a steady kind, plants and the land, and the [extra] series each week, with [change] applied to its series. */
    private fun history(extra: Map<String, Float> = emptyMap(), change: (Map<String, HexHistory.FloatList>) -> Unit = {}): HexHistory {
        val h = HexHistory()
        val series = (mapOf(Key.animals("roe deer") to 1000f, Key.biomass(StockName.PLANTS) to 5e6f, Key.LAND_AREA to 1e6f, Key.TEMPERATURE to 10f, Key.RAIN to 10f) + extra)
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
            s.getValue(Key.biomass(StockName.PLANTS)).add(-1f)
            s.getValue(Key.TEMPERATURE).add(Float.POSITIVE_INFINITY)
            for (k in listOf(Key.LAND_AREA, Key.RAIN)) s.getValue(k).add(s.getValue(k)[0])
        }.also { it.year.add(3f); it.month.add(0f) })
        val what = r.findings.map { it.what }.toSet()
        assertTrue(Key.animals("roe deer") in what && Key.biomass(StockName.PLANTS) in what && Key.TEMPERATURE in what, r.report())
    }

    @Test
    fun `a kind falling tenfold in a year is found`() {
        val r = Nominal.history(history { s -> val a = s.getValue(Key.animals("roe deer")); for (i in 2 * WorldConfig().stepsPerYear until weeks) a.data[i] = 100f })
        assertTrue(r.findings.any { it.what == Key.animals("roe deer") }, r.report())
    }

    @Test
    fun `a fruit crop swinging fifteen-fold with the mast and kept animals growing from nothing pass, a plain pool swinging thirty-fold is found`() {
        val w = WorldConfig().stepsPerYear
        val fruit = Key.biomass(StockName.FRUIT); val kept = Key.biomass(StockName.BANDS_KEPT_ANIMALS); val carrion = Key.biomass(StockName.CARRION)
        val extra = mapOf(fruit to 1000f, kept to 0.0001f, carrion to 1000f)
        val r = Nominal.history(history(extra) { s ->
            for (i in w until 2 * w) { s.getValue(fruit).data[i] = 66f; s.getValue(carrion).data[i] = 33f }
            for (i in 2 * w until weeks) s.getValue(kept).data[i] = 1f
        })
        val what = r.findings.map { it.what }.toSet()
        assertTrue(fruit !in what && kept !in what && carrion in what, r.report())
    }

    @Test
    fun `deaths that do not add up to the heads lost are found, and those that do pass`() {
        val dead = Key.deaths("roe deer", "hunger")
        val off = Nominal.history(history(mapOf(dead to 10f)))
        assertTrue(off.findings.any { it.what == "deaths of roe deer" }, off.report())
        val even = Nominal.history(history(mapOf(dead to 10f, Key.births("roe deer") to 10f)))
        assertTrue(even.ok, even.report())
    }

    @Test
    fun `people taken by beasts beyond what foragers lose are found, and a few pass`() {
        val people = Catalogue.HUMAN.name
        val taken = Key.deaths(people, ByBeast.of(ByBeast.How.TAKEN_BY, Catalogue.LION).label)
        fun run(perWeek: Float) = Nominal.history(history(mapOf(Key.animals(people) to 1000f, taken to perWeek, Key.births(people) to perWeek)))
        assertTrue(run(0.1f).findings.any { it.what == "people taken by beasts" }, run(0.1f).report())
        assertTrue(run(0.005f).findings.none { it.what == "people taken by beasts" }, run(0.005f).report())
    }

    @Test
    fun `bands forgetting hunting and going unclad are found`() {
        val r = Nominal.history(history(mapOf(Key.people(HexHistory.PeopleWay.HUNTING) to 0.05f, Key.people(HexHistory.PeopleWay.CLAD) to 0.3f,
            Key.people(HexHistory.PeopleWay.FIRE) to 1f)))
        val what = r.findings.map { it.what }.toSet()
        assertTrue(Key.people(HexHistory.PeopleWay.HUNTING) in what && Key.people(HexHistory.PeopleWay.CLAD) in what &&
            Key.people(HexHistory.PeopleWay.FIRE) !in what, r.report())
    }

    @Test
    fun `a small world's state after some weeks is within limits`() {
        val sim = HexSim(World.generate(WorldConfig(seed = 3, widthKm = 200, heightKm = 200, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)))
        repeat(10) { sim.step() }
        val r = Nominal.state(sim)
        // (Bands start with half a kit of clothes, which wears below half before they make more: a fresh world's bands
        // are short of the clothes a settled world's have.)
        assertTrue(r.findings.all { it.what == "people, " + HexHistory.PeopleWay.CLAD.label }, r.report())
    }
}
