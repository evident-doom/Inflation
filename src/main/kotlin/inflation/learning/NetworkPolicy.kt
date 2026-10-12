package inflation.learning

import inflation.ecology.AnimalGroup
import inflation.ecology.Catalogue
import inflation.ecology.Option
import inflation.ecology.Policy
import inflation.ecology.Species
import inflation.ecology.headingMatches
import inflation.ecology.pickBest
import java.io.File
import kotlin.math.min

/**
 * What a group knows about itself and one place it could go, as numbers for a network: the same
 * inputs the baseline sees, scaled to roughly 0..1. Body constants (diet, habitat, limits) are not
 * inputs, as each species has its own network.
 */
object Features {
    const val SIZE = 26

    fun encode(g: AnimalGroup, o: Option, k: Int, out: FloatArray) {
        val s = g.species
        val urgency = g.daysWithoutWater / s.daysWithoutWater
        out[0] = min(2f, urgency)
        out[1] = if (g.daysWithoutWater >= s.daysWithoutWater - 1) 1f else 0f
        out[2] = g.condition
        out[3] = if (g.alert > 0) 1f else 0f
        out[4] = if (g.marching) 1f else 0f
        out[5] = o.forage / (o.forage + 50f)
        out[6] = o.forage / (o.forage + 100f)
        out[7] = o.danger
        out[8] = o.prey
        out[9] = o.kin
        out[10] = min(3f, o.waterKm / s.dailyTravelKm)
        out[11] = min(3f, o.waterKm / 10f)
        out[12] = if (o.snowMm > s.snowLimitMm) 1f else 0f
        out[13] = o.cover
        out[14] = o.rugged
        out[15] = o.wet
        out[16] = if (k == 0) 0f else o.km / s.maxDailyTravelKm
        out[17] = min(2f, o.thermal)
        out[18] = o.pull
        out[19] = o.hazard
        out[20] = min(3f, o.rainAhead) / 3f
        out[21] = if (k == 0) 1f else 0f
        out[22] = if (headingMatches(g, k)) 1f else 0f
        out[23] = min(1f, g.weeksHere / 3f)
        out[24] = g.lastIntakeRatio
        out[25] = if (g.homeX.isNaN()) 1f else 0f
    }
}

/**
 * Chooses with one small network per species that scores each option from [Features]; the best
 * score plus the same small noise as the baseline wins. Species without a network fall back to
 * [fallback].
 */
class NetworkPolicy(
    val nets: Map<Species, Mlp>,
    private val noise: (AnimalGroup, Int) -> Float,
    private val fallback: Policy,
) : Policy {
    private val buffers = ThreadLocal.withInitial { HashMap<Mlp, Mlp.Buffers>() }

    override fun choose(group: AnimalGroup, options: List<Option>): Int {
        val net = nets[group.species] ?: return fallback.choose(group, options)
        val b = buffers.get().getOrPut(net) { net.buffers() }
        return pickBest(group, options, noise) { o, k ->
            Features.encode(group, o, k, b.a[0])
            net.forward(b)
        }
    }

    companion object {
        /** Directory of saved networks in the repository, one file per species. */
        val DIR = File("policies")

        fun file(dir: File, s: Species) = File(dir, s.name.replace(' ', '-') + ".mlp")

        /** Networks saved in [dir] for any of [Catalogue.all]. */
        fun load(dir: File = DIR): Map<Species, Mlp> =
            Catalogue.all.mapNotNull { s -> file(dir, s).takeIf { it.exists() }?.let { s to Mlp.load(it.readText()) } }.toMap()
    }
}
