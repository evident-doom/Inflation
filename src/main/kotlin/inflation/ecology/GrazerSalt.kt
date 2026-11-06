package inflation.ecology

import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.Place
import inflation.world.Hex
import inflation.world.get
import inflation.world.set
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// Plant-eaters' sodium: wild and kept alike. Plants carry little sodium, the less the farther from the sea whose spray
// brings it, and a plant-eater short of it bears fewer young; a salt lick in its hex makes up the want. One rule for the
// residents, the roaming herds and a band's kept animals, whose keepers make the want up with their own salt.

/**
 * The sodium in plant forage at each place, as a share of its dry matter, or [LICK] where its hex has a
 * salt lick ([HexLand.salt]: a salt pan or salt lake shore, a desert shore) or lies on the coast ([LICK]: as much as any need): [FORAGE_SODIUM_INLAND]
 * far inland, rising toward [FORAGE_SODIUM_COAST] at the sea over [MARINE_KM] (the sea's spray falls off inland).
 */
internal fun forageSodium(hw: HexWorld, land: HexLand): FloatArray {
    val hex = hw.hex
    val steps = IntArray(hex.size) { -1 }
    val queue = ArrayDeque<Hex>()
    for (h in hex.all) if (hw.coast[h]) { steps[h] = 0; queue.addLast(h) }
    while (queue.isNotEmpty()) {
        val h = queue.removeFirst()
        for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && steps[q] < 0) { steps[q] = steps[h] + 1; queue.addLast(q) } }
    }
    val lick = BooleanArray(hex.size)
    for (p in land.places) if (land.salt[p] > 0f) lick[hw.placeHex[p]] = true
    return FloatArray(land.n) { i ->
        val h = hw.placeHex[Place(i)]
        if (lick[h] || hw.coast[h]) LICK else {
            if (steps[h] < 0) FORAGE_SODIUM_INLAND
            else FORAGE_SODIUM_INLAND + (FORAGE_SODIUM_COAST - FORAGE_SODIUM_INLAND) * exp(-(steps[h] * hex.hexKm).toFloat() / MARINE_KM)
        }
    }
}

/**
 * How well kind [s]'s food meets its need for sodium where the forage holds [forage] of its dry matter ([forageSodium]),
 * 0..1: its animal food meets it in full, its plant food as far as it holds [GRAZER_SODIUM_NEED].
 */
internal fun sodiumMet(s: Species, forage: Float): Float {
    val d = s.diet
    val plants = d.grass + d.browse + d.roots + d.fruit
    if (plants <= 0f) return 1f
    val animal = d.animal
    val share = plants / (plants + animal)
    return (1f - share) + share * min(1f, forage / GRAZER_SODIUM_NEED)
}

/** The share of the young a mother of [s] bears that her sodium, met [met] ([sodiumMet]), allows: [lack] fewer wholly without ([inflation.Tuning.grazerSaltLack]). */
internal fun saltBreeding(met: Float, lack: Float) = 1f - lack * (1f - met.coerceIn(0f, 1f))

/** [saltBreeding] for kind [s] at place [p] on its forage alone. */
internal fun HexFauna.saltBreeding(s: Species, p: Place) = saltBreeding(sodiumMet(s, forageSodiumAt[p]), tuning.grazerSaltLack)

/**
 * Salt band [b] gives its kept animals this week, [heads] of kind [s] at a camp where the forage holds [forage] sodium:
 * what makes up their want ([GRAZER_SODIUM_NEED] of their intake, [Species.dailyIntakeKg]), as far as its salt goes;
 * how well their sodium is met after it, 0..1. (Herders carried salt for their beasts and drove them to licks.)
 */
internal fun feedHerdSalt(b: Band, s: Species, heads: Float, forage: Float, days: Int): Float {
    val met = sodiumMet(s, forage)
    if (met >= 1f || heads <= 0f) return 1f
    val want = heads * s.dailyIntakeKg * days * GRAZER_SODIUM_NEED * (1f - met) / SALT_SODIUM
    val given = min(want, max(0f, b.goods[Material.SALT]))
    b.goods[Material.SALT] -= given
    return met + (1f - met) * (given / want)
}

/**
 * Sodium plant-eaters need, as a share of their food's dry matter: 0.06-0.08% for growing and gestating cattle, 0.1% in
 * milk (NASEM 2016, Nutrient Requirements of Beef Cattle; J Anim Sci 50:145, 1980). Taken for every plant-eater.
 */
internal const val GRAZER_SODIUM_NEED = 0.0007f
/**
 * Sodium in forage far inland and at the sea, shares of dry matter, and how fast the sea's spray falls off inland, km.
 * Guesses: plants carry orders of magnitude less sodium than animals (Dudley et al. 2012, Biotropica 44:6), and
 * North American ungulates use salt licks more than chance beyond some 500 km of the coast (Maro and Dudley 2022,
 * J Biogeogr 49:254), so forage is taken to fall short of the need some 300-400 km inland.
 */
internal const val FORAGE_SODIUM_INLAND = 0.0003f
internal const val FORAGE_SODIUM_COAST = 0.002f
internal const val MARINE_KM = 300f
/** The forage sodium [forageSodium] takes for a hex with a salt lick or on the coast: all the need, and more. */
internal const val LICK = 1f
