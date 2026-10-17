package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A year of bands (their week in [bandWeek], foraging in [forage], their rites, ties and descent) leaves each band's
 * state within its bounds: no stock below nothing or not a number, shares within 0..1, the old and the
 * age spans no more than the adults.
 */
class BandStateTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `a year leaves every band's state within its bounds`() {
        val sim = HexSim(World.generate(cfg))
        repeat(52) { sim.step() }
        // (People live in bands and only people do, so "is a band" can be read either way.)
        for (g in sim.groups.groups) if (g.alive) assertEquals(g.species.human, g.band != null, "group ${g.id} (${g.species.name})")
        val bands = sim.groups.groups.filter { it.alive && it.band != null }
        assertTrue(bands.isNotEmpty(), "no bands lived through the year, so nothing was checked")
        val bad = ArrayList<String>()
        fun check(g: HexGroup, what: String, ok: Boolean) { if (!ok) bad += "band ${g.id}: $what" }
        fun FloatArray.allIn(lo: Float, hi: Float) = all { it.isFinite() && it >= lo && it <= hi }
        for (g in bands) {
            val b = g.band!!
            check(g, "goods ${b.goods.toList()}", b.goods.allIn(0f, Float.MAX_VALUE))
            check(g, "stores ${g.stores.toList()}", g.stores.allIn(0f, Float.MAX_VALUE))
            check(g, "cache ${b.cache.toList()}", b.cache.allIn(0f, Float.MAX_VALUE))
            check(g, "kit ${b.kit.toList()}", b.kit.allIn(0f, 1.0001f))
            check(g, "make of its tools ${b.fine.toList()}", b.fine.allIn(0f, 1.0001f))
            check(g, "skills ${b.skills.toList()}", b.skills.allIn(0f, Float.MAX_VALUE))
            for ((name, v) in listOf("house" to b.house, "palisade" to b.palisade, "shelter" to b.shelter, "hurt" to b.hurt, "lame" to b.lame, "night wood" to b.nightWood))
                check(g, "$name $v", v.isFinite() && v in 0f..1.0001f)
            check(g, "spare days ${b.spareDays}", b.spareDays.isFinite() && b.spareDays >= 0f)
            check(g, "elders ${b.elders} and age spans ${b.ageStage.toList()} of ${g.adults} adults", b.elders >= 0 && b.ageStage.all { it >= 0 } && b.elders + b.ageStage.sum() <= g.adults)
            check(g, "rite places ${b.rite.toList()}", b.rite.all { it >= -1 })
            check(g, "ties ${b.ties}", b.ties.values.all { it.isFinite() })
            // (Their culture: grudges and descent are amounts, inbreeding a share.)
            check(g, "grudges ${b.grudge.toList()}", b.grudge.allIn(0f, Float.MAX_VALUE))
            check(g, "lineage ${b.lineage.toList()}", b.lineage.allIn(0f, Float.MAX_VALUE))
            check(g, "inbred ${b.inbred}", b.inbred.isFinite() && b.inbred in 0f..1f)
            check(g, "hold on its rites ${b.riteStrength}", b.riteStrength.isFinite() && b.riteStrength >= 0f)
            check(g, "fed ${g.fed}, reserves ${g.fat}", g.fed.isFinite() && g.fed in 0f..1.0001f && g.fat.isFinite() && g.fat >= 0f)
        }
        assertEquals(emptyList(), bad)
    }
}
