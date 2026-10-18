package inflation.tools.fauna

import inflation.ecology.Thresholds
import inflation.Config
import inflation.ecology.HexFood
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.hexOf
import inflation.ecology.m2

/**
 * The extra lines a fauna check prints when the config asks for them:
 * - huntDiag=KIND,...: four times a year, that kind's hungry packs against its fed ones, and the thirsty, with their ground;
 * - fedDiag=true: four times in the first year, how well each kind is fed where it lives, and why;
 * - follow=KIND: one group of that kind week by week, as the viewer's follow mode does;
 * - migDiag=KIND: each month, how far that kind's herds are from the place they remember for the month;
 * - crowdDiag=KIND: each month, the crowd that kind's herds foresaw where they chose to go against the one they met there;
 * - deathDiag=KIND: each quarter, the deaths that kind's herds foresaw by cause where they chose to go against those they met.
 */
class Diagnostics(private val sim: HexSim, private val daysPerStep: Int) {
    private val hw = sim.hw
    private val land = sim.land
    private val fauna = sim.fauna
    private val groups = sim.groups
    private val weather get() = sim.weather

    fun beforeStep(y: Int, w: Int) {
        Config.string("huntDiag")?.let { kinds -> if (w % 13 == 6) for (kind in kinds.split(',')) hunters(w, kind) }
        if (Config.flag("fedDiag", false) && y == 1 && w % 13 == 6) fed(w)
        Config.string("follow")?.let { kind -> if (groups.groups.none { it.id == groups.watched && it.alive }) groups.watched =
            groups.groups.firstOrNull { it.alive && it.species.name == kind }?.id ?: -1 }
    }

    fun afterStep() {
        if (Config.string("follow") != null && groups.watched >= 0) {
            val g = groups.groups.firstOrNull { it.id == groups.watched }
            println("   follow #%d (%s, %d) %s:".format(groups.watched, g?.species?.name ?: "?", g?.size ?: 0, weather.date))
            for (l in groups.watchLog) println("      $l")
        }
        Config.string("migDiag")?.let { kind -> if (weather.date.dayOfMonth <= daysPerStep) migration(kind) }
        Config.string("crowdDiag")?.let { kind -> crowding(kind, weather.date.dayOfMonth <= daysPerStep) }
        Config.string("deathDiag")?.let { kind -> deaths(kind, weather.date.dayOfMonth <= daysPerStep && weather.date.month % 3 == 0) }
    }

    private val foreseenBy = java.util.TreeMap<String, Double>(); private val metBy = java.util.TreeMap<String, Double>()
    private var deathSums = DoubleArray(6)
    /**
     * The animals each herd of [kind] foresaw would die this week where it chose to go ([inflation.ecology.HexGroups.foreseenDeaths]:
     * its share by cause at its heads now), against those that died, by cause (the groups' tally, with the kills of the
     * hunting parties and of the residents' hunters); and how fed it foresaw it would be against how fed it was, and its
     * births. Summed weekly, printed each quarter ([print]). (What it foresaw leaves out age and the background, the same
     * wherever it goes: those show as met only.)
     */
    private fun deaths(kind: String, print: Boolean) {
        val s = groups.kinds.firstOrNull { it.name == kind }
        if (s != null) {
            val byId = HashMap<Int, HexGroup>(); for (g in groups.groups) if (g.species == s) byId[g.id] = g
            for ((id, m) in groups.foreseenDeaths) {
                val g = byId[id] ?: continue
                deathSums[5] += g.size.toDouble()
                for ((cause, v) in m) foreseenBy.merge(cause, (g.adults * v[0] + g.young * v[1]).toDouble(), Double::plus)
                groups.foreseenFed[id]?.let { deathSums[0] += 1.0; deathSums[1] += it.toDouble(); deathSums[2] += g.fed.toDouble() }
            }
            for ((key, v) in groups.stats.deaths) if (key.startsWith("$kind:")) metBy.merge(key.substringAfter(':'), v.toDouble(), Double::plus)
            metBy.merge("hunting parties", groups.herdKilledByGroups[s.index].toDouble(), Double::plus)
            metBy.merge("resident hunters", groups.herdKilledByResidents[s.index].toDouble(), Double::plus)
            groups.herdKilledByResidents[s.index] = 0
            for (g in groups.groups) if (g.alive && g.species == s) deathSums[3] += g.size.toDouble()
            deathSums[4] += (groups.stats.births[kind] ?: 0).toDouble()
            groups.foreseenDeaths.clear(); groups.foreseenFed.clear()
        }
        if (print && deathSums[0] > 0.0) {
            val headWeeks = deathSums[3].coerceAtLeast(1.0)
            println("     deaths %s %s: foreseen / met, per 1000 head-weeks: ".format(kind, weather.date) +
                (foreseenBy.keys + metBy.keys).toSortedSet().joinToString(", ") { c ->
                    "%s %.1f / %.1f".format(c, 1000.0 * (foreseenBy[c] ?: 0.0) / headWeeks, 1000.0 * (metBy[c] ?: 0.0) / headWeeks) } +
                "; all %.1f / %.1f".format(1000.0 * foreseenBy.values.sum() / headWeeks, 1000.0 * metBy.values.sum() / headWeeks))
            println("     deaths %s %s: fed foreseen %.2f, met %.2f; births %.1f per 1000 head-weeks; %.0f%% of head-weeks weighed their places".format(
                kind, weather.date, deathSums[1] / deathSums[0], deathSums[2] / deathSums[0], 1000.0 * deathSums[4] / headWeeks, 100.0 * deathSums[5] / headWeeks))
            foreseenBy.clear(); metBy.clear(); deathSums = DoubleArray(6)
        }
    }

    private var crowdSums = DoubleArray(12)
    /**
     * The crowd (as the herds' sickness reckons it, 1 = its kind's usual crowding) each herd of [kind] that chose its own
     * place this week foresaw there, against the one it met after all had moved; summed weekly, printed each month ([print]).
     */
    private fun crowding(kind: String, print: Boolean) {
        val k = groups.kinds.indexOfFirst { it.name == kind }
        if (k >= 0) {
            val heads = HashMap<Int, Int>()
            for (g in groups.groups) if (g.alive && g.species.name == kind) heads.merge(hw.placeHex[g.place], 1, Int::plus)
            for (g in groups.groups) {
                if (!g.alive || g.species.name != kind) continue
                val foreseen = groups.foreseenCrowd[g.id] ?: continue
                val met = groups.metCrowd[g.id] ?: continue
                crowdSums[0] += 1.0; crowdSums[1] += foreseen.toDouble(); crowdSums[2] += met.toDouble()
                if (met > 2f * foreseen + 0.05f) crowdSums[3] += 1.0
                crowdSums[4] += g.size.toDouble(); crowdSums[5] += (heads[hw.placeHex[g.place]] ?: 1).toDouble()
                val asPlanned = groups.metAsPlannedCrowd[g.id] ?: met
                crowdSums[6] += asPlanned.toDouble(); if (groups.reachedChoice[g.id] == true) crowdSums[7] += 1.0
                if (met > 2f * foreseen + 0.05f) { crowdSums[8] += 1.0; crowdSums[9] += foreseen.toDouble(); crowdSums[10] += asPlanned.toDouble(); crowdSums[11] += met.toDouble() }
            }
            groups.foreseenCrowd.clear(); groups.metCrowd.clear(); groups.metAsPlannedCrowd.clear(); groups.reachedChoice.clear(); groups.choiceDiag.clear()
        }
        if (print && crowdSums[0] > 0.0) {
            val n = crowdSums[0]
            println("     crowding %s %s: %d choices, foreseen %.2f, met %.2f, met over twice foreseen %.0f%%, herd size %.0f, herds of its kind in its hex %.1f".format(
                kind, weather.date, n.toInt(), crowdSums[1] / n, crowdSums[2] / n, 100.0 * crowdSums[3] / n, crowdSums[4] / n, crowdSums[5] / n))
            // (Met as planned: where it ended the week, had the others stayed as they were when it chose. Foreseen to that is
            // its own ground and place changing; that to met is the others moving.)
            val m = crowdSums[8].coerceAtLeast(1.0)
            println("     crowding %s %s: met as planned %.2f, reached its choice %.0f%%; of those over twice: foreseen %.2f, met as planned %.2f, met %.2f".format(
                kind, weather.date, crowdSums[6] / n, 100.0 * crowdSums[7] / n, crowdSums[9] / m, crowdSums[10] / m, crowdSums[11] / m))
            crowdSums = DoubleArray(12)
        }
    }

    /** Packs of [kind] that went hungry this week against those that ate, and their ground; and the thirsty ones. */
    private fun hunters(w: Int, kind: String) {
        val gs = groups.groups.filter { it.alive && it.species.name == kind }
        val (hungry, fed) = gs.partition { it.fed < Thresholds.HUNGRY }
        println("     week $w $kind: ${hungry.size} of ${gs.size} packs hungry: ${ground(hungry)} | fed: ${ground(fed)}")
        val thirsty = gs.filter { it.dry > it.species.daysWithoutWater }
        if (thirsty.isNotEmpty()) println("     week $w $kind: ${thirsty.size} thirsty: " + ground(thirsty) + "; water within reach %.0f%%, sea %.0f%%"
            .format(100.0 * thirsty.count { land.drink.water[it.place] >= 0.05f } / thirsty.size,
                100.0 * thirsty.count { hw.sea[hw.placeHex[it.place]] > 0.3f } / thirsty.size))
    }

    /** What the groups [l] have about them and how they fare, on average. */
    private fun ground(l: List<HexGroup>): String {
        if (l.isEmpty()) return "-"
        fun mean(f: (HexGroup) -> Double) = l.map(f).average()
        return "resident game %.0f kg/km², young per pack %.1f, holding ground %.0f%%, cover %.2f, water %.1f km, band %.1f".format(
            mean { g -> fauna.kinds.indices.sumOf { k -> ((fauna.adults[k][g.place] + fauna.young[k][g.place]) * fauna.kinds[k].massKg).toDouble() } },
            mean { it.young.toDouble() }, 100.0 * l.count { !it.home.none } / l.size,
            mean { land.plants.cover(it.place.i).toDouble() }, mean { land.drink.waterKm[it.place.i].toDouble() }, mean { hw.placeBand[it.place.i].toDouble() }) +
            ", %.0f m, %.1f °C, fed %.2f, larder %.0f days, size %.0f, path %.1f hexes, days without water %.1f".format(
                mean { hw.placeElevationM[it.place].toDouble() }, mean { weather.temperature[it.place].toDouble() },
                mean { it.fed.toDouble() }, mean { (it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f)).toDouble() }, mean { it.size.toDouble() },
                mean { it.path.size.toDouble() }, mean { it.dry.toDouble() })
    }

    /** How well each resident kind is fed where it lives, and why, weighted by head count; and the herds and packs. */
    private fun fed(w: Int) {
        println("     week $w, residents: fed / share hungry / tether / access / green / reserve days")
        for ((k, s) in fauna.kinds.withIndex()) {
            var heads = 0.0; var fed = 0.0; var hungry = 0.0; var teth = 0.0; var acc = 0.0; var gr = 0.0; var res = 0.0
            for (p in 0 until fauna.n) {
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                if (h <= 0.0) continue
                heads += h; fed += h * fauna.fed[k][p]; if (fauna.fed[k][p] < 0.6f) hungry += h
                teth += h * fauna.tether(s, p); acc += h * HexFood.access(s, land.crag[p]); gr += h * land.plants.green[p]; res += h * fauna.reserve[k][p]
            }
            if (heads > 0) println("       %-12s %.2f / %.0f%% / %.2f / %.2f / %.2f / %.0f".format(s.name, fed / heads, 100 * hungry / heads,
                teth / heads, acc / heads, gr / heads, res / heads))
        }
        println("     herds and packs: fed / reserve days / young per adult / days without water / cold degrees")
        for (s in groups.kinds) {
            val gs = groups.groups.filter { it.alive && it.species == s }
            val heads = gs.sumOf { it.size }.toDouble().coerceAtLeast(1.0)
            println("       %-12s %.2f / %.0f / %.2f / %.1f / %.1f".format(s.name, gs.sumOf { it.fed.toDouble() * it.size } / heads,
                gs.sumOf { it.fat.toDouble() * it.size } / heads,
                gs.sumOf { it.young }.toDouble() / gs.sumOf { it.adults }.coerceAtLeast(1), gs.sumOf { it.dry.toDouble() * it.size } / heads,
                gs.sumOf { s.coldC(weather.temperature[it.place], weather.date.month).toDouble() * it.size } / heads))
        }
    }

    private fun migration(kind: String) {
        val m = weather.date.month
        val gs = groups.groups.filter { it.alive && it.species.name == kind && it.seasonPlace[m] >= 0 }
        if (gs.isNotEmpty()) println("     month ${m + 1} $kind: %.0f km from this month's place, fed %.2f, remembered worth %.2f".format(
            gs.map { sim.hex.distanceKm(hw.placeHex[it.place], hw.placeHex[it.seasonPlace[m]]) }.average(), gs.map { it.fed.toDouble() }.average(),
            gs.map { it.seasonQ[m].toDouble() }.average()))
    }
}
