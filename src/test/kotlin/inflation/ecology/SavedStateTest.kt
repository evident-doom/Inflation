package inflation.ecology

import inflation.store.fields
import inflation.world.World
import inflation.world.WorldConfig
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a save holds should change as the model runs: an array a year of running leaves as it was is most
 * likely worked out from the world (a slope, a normal, a river's course), and saving it would carry an
 * old version of it over a fix into a run carried on from a save. Such fields are @Transient; the ones
 * here only happen not to change on this small world in its first year.
 */
class SavedStateTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    private fun hashes(roots: List<Pair<String, Any>>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val seen = IdentityHashMap<Any, Boolean>()
        fun walk(path: String, o: Any?) {
            if (o == null) return
            when (o) {
                is FloatArray -> { out[path] = o.contentHashCode(); return }
                is IntArray -> { out[path] = o.contentHashCode(); return }
                is DoubleArray -> { out[path] = o.contentHashCode(); return }
                is BooleanArray -> { out[path] = o.contentHashCode(); return }
                is ByteArray -> { out[path] = o.contentHashCode(); return }
                is LongArray -> { out[path] = o.contentHashCode(); return }
                is ShortArray -> { out[path] = o.contentHashCode(); return }
                is Array<*> -> { if (o.all { it is FloatArray || it is IntArray || it is DoubleArray || it is BooleanArray }) out[path] = o.contentDeepHashCode(); return }
            }
            val c = o.javaClass
            if (!HexSnapshot.saved(c) || c.isEnum) return
            if (seen.put(o, true) != null) return
            for (f in fields(c)) walk("$path.${f.name}", f.get(o))
        }
        for ((n, r) in roots) walk(n, r)
        return out
    }

    private val stillOnThisWorld = setOf(
        "weather.meltwater", "weather.meltSun", "weather.meltShade", "land.hydro.flood", "land.hydro.soak", "land.plants.cured",
        "land.plants.curedShade", "land.plants.ash", "land.fire.burning", "land.fire.burnt", "fauna.huntedByResidents", "fauna.small.locusts",
        "fauna.small.returning", "fauna.small.waiting", "fauna.small.leaving", "fauna.small.newborn", "fauna.water.run", "fauna.water.penned",
        "fauna.water.runStock", "fauna.water.runWaiting", "fauna.food.cereal", "fauna.food.domestic", "fauna.food.field",
        "fauna.food.ripenedNow", "fauna.food.fieldGrain", "fauna.food.fieldDomestic", "fauna.food.fieldDays", "fauna.food.fieldStress", "fauna.food.fieldSoil", "fauna.food.fieldWorn",
        // (A worn route forms from many crossings: on this small world a year may or may not see the first one, or a way along it.)
        "groups.routes.legWear", "groups.routes.onRoute", "groups.routes.way",
        // (Grave goods need a death in a band that owns ornaments: on this small world a year may or may not see one.)
        "land.graveGoods",
        // (Burials need a death in a band that keeps the burial tradition: on this small world a year may or may not see one.)
        "land.burials",
        // (Snow iced over by rain on it: the small test world may see no rain on lying snow in its year.)
        "land.drink.iced",
        // (Snow lies on this world's hills only some winter weeks, and the walk to water and residents' days without it
        // change only in a dry spell: the year may begin and end in April with no snow, the same water and none gone dry.)
        "weather.snowpack", "weather.snowSun", "weather.snowShade", "land.drink.snowWater", "land.drink.waterKm", "fauna.dry",
        // (Soil fertility moves only with ash from a fire or the wash off bare slopes: grazed at their settled numbers from
        // the start, this world's plants may give neither in its first year.)
        "land.plants.fertility",
    )

    @Test
    fun `what is saved changes as the model runs`() {
        val sim = HexSim(World.generate(cfg))
        val before = hashes(HexSnapshot.roots(sim))
        repeat(52) { sim.step() }
        val after = hashes(HexSnapshot.roots(sim))
        val still = before.filter { (k, v) -> after[k] == v && k !in stillOnThisWorld }.keys
        assertTrue(still.isEmpty(), "saved, but a year leaves them as they were (worked out from the world? then @Transient): $still")
    }
}
