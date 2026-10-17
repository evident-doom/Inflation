package inflation.ecology

import inflation.Kg
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * EXPERIMENT (branch only, not for main): the herds a hunter came on this week are foods in its one diet pass
 * ([forage]), as resident game is, instead of chased where they were met. The meetings still happen in [meet]
 * (and a herd that sees the hunters first still turns aside there), but whether the hunter tries them, and how
 * many, is settled with the rest of its week's food: it chases a herd only while it is hungry and the herd is
 * worth it against what else is about, for the share of the week it searched. What is lost: the herds it
 * chased no longer stop where they scattered, nor does the hunter stop to eat (their ways are settled before
 * [forage] runs); and a chase no longer alarms the herd for its later meetings that week.
 */

/** EXPERIMENT switch: herds met are foods in the hunter's one diet pass (see [HerdMet]); off: chased where met. */
internal val HERDS_IN_DIET = inflation.Config.flag("t.herdsInDiet", false)

/**
 * A herd [herd] its hunter came on unseen this week at place [p], on [day]: [bands] of its kind's usual bands it
 * could try (each once: a band chased scatters), each try a kill at [chance].
 */
class HerdMet(val herd: HexGroup, val p: Int, var bands: Int, val chance: Float, val day: Double) {
    /** The hunter's search image for its kind when it forages, 0..1 (see [SEARCH_HALF]). */
    var image = 1f
}

/** [hunterMeetsHerd]'s note of a herd met unseen, for the hunter's diet pass; false if it is beyond the hunter's reach. */
internal fun HexGroups.noteHerdMet(hunter: HexGroup, herd: HexGroup, p: Int, met: Int, wary: Float, day: Double): Boolean {
    val packKg = max(1, hunter.adults) * hunter.species.massKg
    val catchable = catchable(hunter.species, herd.species, p, packKg)
    if (catchable < NOT_WORTH_TRYING) { diag(herd.species, "herd beyond reach", met.toFloat()); return false }
    val chance = killChance(hunter, herd.species, catchable, wary, bandsIn(herd))
    val list = hunter.herdsMetWeek ?: ArrayList<HerdMet>().also { hunter.herdsMetWeek = it }
    list += HerdMet(herd, p, max(1, min(met, ceil(bandsIn(herd)).toInt())), chance, day)
    return true
}

/** [forage]'s offers of the herds [g] came on: a kill of each at its rate a day of searching, kg and days. */
internal fun HexGroups.offerHerds(g: HexGroup, packKg: Float, offer: (Food, Int, Int, Float, Float, Float) -> Unit) {
    val list = g.herdsMetWeek ?: return
    for ((i, hm) in list.withIndex()) {
        if (hm.bands <= 0 || !hm.herd.alive || hm.herd.adults <= 0) continue
        val kg = killKg(g, hm.herd.species)
        offer(Food.HERD, i, hm.p, hm.bands * hm.image * hm.chance / days, kg, killDays(hm.chance, kg, packKg))
    }
}

/** Sets each herd's search image as [g] sets out to forage, on [day]. */
internal fun HerdMet.lookFor(g: HexGroup, day: Double) { image = g.metLately(herd.species.index, day).let { it / (it + SEARCH_HALF) } }

/** The herds [g] came on unseen, counted towards its search image for the [searched] share of its week it was looking; then forgotten. */
internal fun HexGroups.countHerdsMet(g: HexGroup, searched: Float, day: Double) {
    val list = g.herdsMetWeek ?: return
    g.herdsMetWeek = null
    if (searched <= 0f) return
    val counts = FloatArray(Catalogue.all.size)
    for (hm in list) counts[hm.herd.species.index] += hm.bands * searched
    g.metGame(counts, day)
}

/**
 * [forage]'s chase of herd met [i], [expected] kills on average: tries drawn for that, as many as the bands it came
 * on at most, each a kill at the meeting's odds. Returns the kills; [out] gets the meat ([kgEach] an adult's worth).
 */
internal fun HexGroups.huntHerdMet(g: HexGroup, i: Int, expected: Float, kgEach: Float, rnd: kotlin.random.Random, out: FloatArray): Int {
    val hm = g.herdsMetWeek!![i]
    val herd = hm.herd; val ps = herd.species
    out[0] = 0f
    // (Other hunters may be at the same herd on other cores.)
    synchronized(herd) {
        if (!herd.alive) return 0
        val tries = min(hm.bands, poisson(expected / hm.chance, rnd))
        if (tries <= 0) return 0
        hm.bands -= tries
        herd.chasedOn = max(herd.chasedOn, hm.day)
        g.band?.practise(Skill.HUNT)
        stats.hunting.merge(Tally.of(g.species, Tally.CHASES), 1, Int::plus)
        diag(ps, "herd tries", tries.toFloat())
        var kills = 0
        for (t in 0 until tries) {
            if (rnd.nextFloat() < hm.chance && herd.adults > 0) {
                kills++
                // (The young keep to the middle of a big herd, out of reach.)
                if (herd.young > 0 && g.band?.holds(Tradition.TABOO_YOUNG) != true && rnd.nextFloat() < 0.5f * bandsIn(herd).pow(-BIG_HERD_SHELTERS)) {
                    herd.young--
                    val y = min(0.3f * ps.massKg, kgEach)
                    out[0] += y
                    small.carrion[hm.p] += (0.3f * ps.massKg - y) / (land.areaKm2[hm.p] * 1e6f)
                } else {
                    herd.adults--
                    out[0] += kgEach
                    g.band?.let { it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER); it.skin(ps, 1) }
                    small.carrion[hm.p] += leftOver(ps, kgEach, g.band) / (land.areaKm2[hm.p] * 1e6f)
                }
                fauna.killed(hm.p, Kg(ps.massKg))
            }
            // Big, armed prey fight back: a horn or a kick now and then kills one of the hunters.
            if (g.adults > 0 && rnd.nextFloat() < fightBack(ps, g.adults * g.species.massKg)) {
                adultsDied(g, 1, rnd)
                stats.deaths.merge("${g.species.name}:${DeathCause.PREY_FOUGHT_BACK}", 1, Int::plus)
                g.band?.wounded(WOUNDED_PER_DEAD, g.adults)
                small.carrion[g.place] += g.species.massKg / (land.areaKm2[g.place] * 1e6f)
            }
        }
        if (kills == 0) return 0
        stats.kills.merge(ps.name, kills, Int::plus)
        diag(ps, "herd kills", kills.toFloat())
        stats.hunting.merge(Tally.of(g.species, Tally.KILLS_FROM_HERDS), kills, Int::plus)
        stats.hunting.merge(Tally.herdSize(sizeClass(herd), "killed"), kills, Int::plus)
        note(g) { "killed %d from a herd of %s".format(kills, ps.name) }; note(herd) { "lost %d to the hunters".format(kills) }
        return kills
    }
}

/** [forage]'s name for food [t] (of kind or herd met [k]), for notes. */
internal fun HexGroups.foodName(g: HexGroup, t: Food, k: Int) = when (t) {
    Food.BIG_GAME -> fauna.kinds[k].name
    Food.HERD -> "herd of " + g.herdsMetWeek!![k].herd.species.name
    else -> t.label
}
