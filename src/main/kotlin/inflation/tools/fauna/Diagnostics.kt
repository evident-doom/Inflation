package inflation.tools.fauna

import inflation.Config
import inflation.ecology.HexFood
import inflation.ecology.HexGroup
import inflation.ecology.HexSim

/**
 * The extra lines a fauna check prints when the config asks for them:
 * - huntDiag=KIND,...: four times a year, that kind's hungry packs against its fed ones, and the thirsty, with their ground;
 * - fedDiag=true: four times in the first year, how well each kind is fed where it lives, and why;
 * - follow=KIND: one group of that kind week by week, as the viewer's follow mode does;
 * - migDiag=KIND: each month, how far that kind's herds are from the place they remember for the month.
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
        Config.string("follow")?.let { kind -> if (groups.groups.none { it.id == groups.watched && it.alive }) groups.watched = groups.groups.firstOrNull { it.alive && it.species.name == kind }?.id ?: -1 }
    }

    fun afterStep() {
        if (Config.string("follow") != null && groups.watched >= 0) {
            val g = groups.groups.firstOrNull { it.id == groups.watched }
            println("   follow #%d (%s, %d) %s:".format(groups.watched, g?.species?.name ?: "?", g?.size ?: 0, weather.date))
            for (l in groups.watchLog) println("      $l")
        }
        Config.string("migDiag")?.let { kind -> if (weather.date.dayOfMonth <= daysPerStep) migration(kind) }
    }

    /** Packs of [kind] that went hungry this week against those that ate, and their ground; and the thirsty ones. */
    private fun hunters(w: Int, kind: String) {
        val gs = groups.groups.filter { it.alive && it.species.name == kind }
        val (hungry, fed) = gs.partition { it.fed < 0.5f }
        println("     week $w $kind: ${hungry.size} of ${gs.size} packs hungry: ${ground(hungry)} | fed: ${ground(fed)}")
        val thirsty = gs.filter { it.dry > it.species.daysWithoutWater }
        if (thirsty.isNotEmpty()) println("     week $w $kind: ${thirsty.size} thirsty: " + ground(thirsty) + "; water within reach %.0f%%, sea %.0f%%".format(100.0 * thirsty.count { land.water[it.place] >= 0.05f } / thirsty.size, 100.0 * thirsty.count { hw.sea[hw.placeHex[it.place]] > 0.3f } / thirsty.size))
    }

    /** What the groups [l] have about them and how they fare, on average. */
    private fun ground(l: List<HexGroup>): String {
        if (l.isEmpty()) return "-"
        fun mean(f: (HexGroup) -> Double) = l.map(f).average()
        return "resident game %.0f kg/km², young per pack %.1f, holding ground %.0f%%, cover %.2f, water %.1f km, band %.1f".format(
            mean { g -> fauna.kinds.indices.sumOf { k -> ((fauna.adults[k][g.place] + fauna.young[k][g.place]) * fauna.kinds[k].massKg).toDouble() } },
            mean { it.young.toDouble() }, 100.0 * l.count { it.home >= 0 } / l.size,
            mean { land.cover(it.place).toDouble() }, mean { land.waterKm[it.place].toDouble() }, mean { hw.placeBand[it.place].toDouble() }) +
            ", %.0f m, %.1f °C, fed %.2f, larder %.0f days, size %.0f, path %.1f hexes, days without water %.1f".format(mean { hw.placeElevationM[it.place].toDouble() }, mean { weather.temperature[it.place].toDouble() },
                mean { it.fed.toDouble() }, mean { (it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f)).toDouble() }, mean { it.size.toDouble() }, mean { it.path.size.toDouble() }, mean { it.dry.toDouble() })
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
                teth += h * fauna.tether(s, p); acc += h * HexFood.access(s, land.crag[p]); gr += h * land.green[p]; res += h * fauna.reserve[k][p]
            }
            if (heads > 0) println("       %-12s %.2f / %.0f%% / %.2f / %.2f / %.2f / %.0f".format(s.name, fed / heads, 100 * hungry / heads, teth / heads, acc / heads, gr / heads, res / heads))
        }
        println("     herds and packs: fed / reserve days / young per adult / days without water / cold degrees")
        for (s in groups.kinds) {
            val gs = groups.groups.filter { it.alive && it.species == s }
            val heads = gs.sumOf { it.size }.toDouble().coerceAtLeast(1.0)
            println("       %-12s %.2f / %.0f / %.2f / %.1f / %.1f".format(s.name, gs.sumOf { it.fed.toDouble() * it.size } / heads, gs.sumOf { it.fat.toDouble() * it.size } / heads,
                gs.sumOf { it.young }.toDouble() / gs.sumOf { it.adults }.coerceAtLeast(1), gs.sumOf { it.dry.toDouble() * it.size } / heads,
                gs.sumOf { s.coldC(weather.temperature[it.place], weather.date.month).toDouble() * it.size } / heads))
        }
    }

    private fun migration(kind: String) {
        val m = weather.date.month
        val gs = groups.groups.filter { it.alive && it.species.name == kind && it.seasonPlace[m] >= 0 }
        if (gs.isNotEmpty()) println("     month ${m + 1} $kind: %.0f km from this month's place, fed %.2f, remembered worth %.2f".format(
            gs.map { sim.hex.distanceKm(hw.placeHex[it.place], hw.placeHex[it.seasonPlace[m]]) }.average(), gs.map { it.fed.toDouble() }.average(), gs.map { it.seasonQ[m].toDouble() }.average()))
    }
}
