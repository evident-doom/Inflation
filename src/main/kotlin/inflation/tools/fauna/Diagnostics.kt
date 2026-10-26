package inflation.tools.fauna

import inflation.ecology.set
import inflation.ecology.get
import inflation.ecology.Thresholds
import inflation.ecology.DialUse
import inflation.ecology.WAYS_OUT_NAMES
import inflation.Config
import inflation.ecology.HexFood
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.world.get
import inflation.world.set
import inflation.world.Place

/**
 * The extra lines a fauna check prints when the config asks for them:
 * - huntDiag=KIND,...: four times a year, that kind's hungry packs against its fed ones, and the thirsty, with their ground;
 * - fedDiag=true: four times in the first year, how well each kind is fed where it lives, and why;
 * - follow=KIND: one group of that kind week by week, as the viewer's follow mode does;
 * - shrubDiag=true: each year, the shrubs' kg gained and lost by each process ([inflation.world.HexPlants.shrubBook]),
 *   with the shrubs standing and the most they could grow to ([inflation.world.HexPlants.ceiling]), summed over the map;
 * - strandDiag=FILE: the residents caught by snow, cold, heat and drought, by kind, month and height ([StrandDiag]).
 * - waysLog=FILE: each band's week, with the numbers it knew of itself and the ways its network set, as CSV ([WaysLog]).
 * - waysForce=WAY:VALUE,...: those ways held at those multiples of the scaffold's for every band, whatever the network sets ([WaysForce]).
 * - dialUse=FILE: how the bands' ways acted where the model reads them, counted over the run and written to FILE as it ends ([DialUse]).
 */
class Diagnostics(private val sim: HexSim) {
    private val hw = sim.hw
    private val land = sim.land
    private val fauna = sim.fauna
    private val groups = sim.groups
    private val weather get() = sim.weather
    private val strand = Config.string(Config.Key.STRAND_DIAG)?.let { StrandDiag(sim, java.io.File(it)) }
    // (The forcing first, so that the log shows the ways as they were held.)
    init {
        Config.string(Config.Key.WAYS_FORCE)?.let { WaysForce(sim, it) }
        Config.string(Config.Key.WAYS_LOG)?.let { WaysLog(sim, java.io.File(it)) }
        Config.string(Config.Key.DIAL_USE)?.let { file ->
            val use = DialUse().also { sim.groups.dialUse = it }
            Runtime.getRuntime().addShutdownHook(Thread { java.io.File(file).writeText(use.report(WAYS_OUT_NAMES)) })
        }
    }

    fun beforeStep(y: Int, w: Int) {
        Config.string(Config.Key.HUNT_DIAG)?.let { kinds -> if (w % 13 == 6) for (kind in kinds.split(',')) hunters(w, kind) }
        if (Config.flag(Config.Key.FED_DIAG, false) && y == 1 && w % 13 == 6) fed(w)
        // (With followHungry, the group followed is the hungriest of its kind fed under [FOLLOW_FED], whenever the one followed is gone or fed again.)
        Config.string(Config.Key.FOLLOW)?.let { kind ->
            if (Config.flag(Config.Key.FOLLOW_HUNGRY, false)) {
                if (groups.groups.none { it.id == groups.watched && it.alive && it.fed < FOLLOW_FED })
                    groups.watched = groups.groups.filter { it.alive && it.species.name == kind && it.fed < FOLLOW_FED }.minByOrNull { it.fed }?.id ?: -1
            } else if (groups.groups.none { it.id == groups.watched && it.alive }) groups.watched =
                groups.groups.firstOrNull { it.alive && it.species.name == kind }?.id ?: -1
        }
    }

    fun afterStep() {
        strand?.week()
        if (Config.string(Config.Key.FOLLOW) != null && groups.watched >= 0) {
            val g = groups.groups.firstOrNull { it.id == groups.watched }
            println("   follow #%d (%s, %d) %s:".format(groups.watched, g?.species?.name ?: "?", g?.size ?: 0, weather.date))
            for (l in groups.watchLog) println("      $l")
        }
        land.plants.shrubBook?.let { book -> val y = weather.date.year; if (shrubYear >= 0 && y != shrubYear) shrubs(book); shrubYear = y }
    }

    private var shrubYear = -1L
    /** shrubDiag: the year's shrub kg by process (then cleared), and the shrubs standing against their ceiling, tonnes. */
    private fun shrubs(book: Array<java.util.concurrent.atomic.DoubleAdder>) {
        val p = land.plants
        var stand = 0.0; var cap = 0.0
        for (i in 0 until hw.placeHex.size) { stand += p.shrub[i] * land.areaM2[i].toDouble(); cap += p.ceiling(inflation.world.LifeForm.SHRUB, Place(i)) * land.areaM2[i].toDouble() }
        println("   shrubDiag %s (tonnes over the map): standing %.4g, ceiling %.4g".format(weather.date, stand / 1000, cap / 1000))
        for ((i, name) in inflation.world.HexPlants.SHRUB_PARTS.withIndex()) println("      %-50s %+.4g".format(name, book[i].sumThenReset() / 1000))
        val n = inflation.world.HexPlants.SHRUB_PARTS.size
        val res = book[n].sumThenReset()
        println("      (ground trodden by residents, km²: %.4g of %.4g)".format(res / 1e6, land.areaM2.sum() / 1e6))
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
            mean { land.plants.cover(it.place).toDouble() }, mean { land.drink.waterKm[it.place.i].toDouble() }, mean { hw.placeBand[it.place.i].toDouble() }) +
            ", %.0f m, %.1f °C, fed %.2f, larder %.0f days, size %.0f, path %.1f hexes, days without water %.1f".format(
                mean { hw.placeElevationM[it.place].toDouble() }, mean { weather.temperature[it.place].toDouble() },
                mean { it.fed.toDouble() }, mean { (it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f)).toDouble() }, mean { it.size.toDouble() },
                mean { it.path.size.toDouble() }, mean { it.dry.toDouble() })
    }

    /** How well each resident kind is fed where it lives, and why, weighted by head count; and the packs and bands. */
    private fun fed(w: Int) {
        println("     week $w, residents: fed / share hungry / tether / access / green / reserve days")
        for ((k, s) in fauna.kinds.withIndex()) {
            var heads = 0.0; var fed = 0.0; var hungry = 0.0; var teth = 0.0; var acc = 0.0; var gr = 0.0; var res = 0.0
            for (p in 0 until fauna.n) {
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                if (h <= 0.0) continue
                heads += h; fed += h * fauna.fed[k][p]; if (fauna.fed[k][p] < 0.6f) hungry += h
                teth += h * fauna.tether(s, Place(p)); acc += h * HexFood.access(s, land.crag[p]); gr += h * land.plants.green[p]; res += h * fauna.reserve[k][p]
            }
            if (heads > 0) println("       %-12s %.2f / %.0f%% / %.2f / %.2f / %.2f / %.0f".format(s.name, fed / heads, 100 * hungry / heads,
                teth / heads, acc / heads, gr / heads, res / heads))
        }
        println("     packs and bands: fed / reserve days / young per adult / days without water / cold degrees")
        for (s in groups.kinds) {
            val gs = groups.groups.filter { it.alive && it.species == s }
            val heads = gs.sumOf { it.size }.toDouble().coerceAtLeast(1.0)
            println("       %-12s %.2f / %.0f / %.2f / %.1f / %.1f".format(s.name, gs.sumOf { it.fed.toDouble() * it.size } / heads,
                gs.sumOf { it.fat.toDouble() * it.size } / heads,
                gs.sumOf { it.young }.toDouble() / gs.sumOf { it.adults }.coerceAtLeast(1), gs.sumOf { it.dry.toDouble() * it.size } / heads,
                gs.sumOf { s.coldC(weather.temperature[it.place], weather.date.month).toDouble() * it.size } / heads))
        }
    }
}

/** followHungry: fed below this share of its want, a group counts as hungry enough to follow. */
private const val FOLLOW_FED = 0.9f
