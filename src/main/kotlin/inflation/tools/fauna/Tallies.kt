package inflation.tools.fauna

import inflation.ecology.groundOfHerds
import inflation.ecology.Tally
import inflation.ecology.Catalogue
import inflation.ecology.Thresholds
import inflation.ecology.DeathCause
import inflation.ecology.WeekStats
import inflation.ecology.Band
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.ecology.Species
import inflation.ecology.Tool
import inflation.ecology.Tradition
import inflation.ecology.boldness
import java.util.TreeMap
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.hexOf
import inflation.ecology.m2
import inflation.world.Date

/** Deaths and births by "kind:cause" over the run, from the model's weekly ledgers; and herds' head-weeks by size. */
class DeathTally(private val sim: HexSim) {
    /** Deaths over the run by "kind:cause" (births as "kind:+born", herd sizes as "herd size N:cause"). */
    val causes = TreeMap<String, Double>()
    /** [causes] as it stood when this year began. */
    private var before: Map<String, Double> = emptyMap()
    /** Herds' head-weeks by size (small, middling, large), to set their deaths against. */
    private val herdHeadWeeks = DoubleArray(3)

    fun startYear() { before = causes.toMap() }

    fun week() {
        val fauna = sim.fauna; val groups = sim.groups
        for ((key, v) in fauna.deaths) causes.merge(key, v, Double::plus)
        for ((key, v) in groups.stats.deaths) causes.merge(key, v.toDouble(), Double::plus)
        for ((key, v) in groups.stats.kills) causes.merge(Tally.of(key, Tally.HUNTED), v.toDouble(), Double::plus)
        for ((key, v) in groups.stats.births) causes.merge(Tally.of(key, Tally.BORN), v.toDouble(), Double::plus)
        for ((key, v) in groups.stats.hunting) if (key.startsWith(Tally.HERD_SIZE)) causes.merge(key, v.toDouble(), Double::plus)
        for (gr in groups.groups) if (gr.alive && !gr.species.carnivore) herdHeadWeeks[groups.sizeClass(gr)] += gr.size.toDouble()
    }

    /** What [key] came to this year. */
    fun thisYear(key: String) = (causes[key] ?: 0.0) - (before[key] ?: 0.0)

    fun printHuntersBornAndDied() {
        println("     hunters this year, born / died: " + sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val born = thisYear(Tally.of(s, Tally.BORN))
            val died = causes.entries.filter { it.key.startsWith(Tally.of(s, "")) && !it.key.endsWith(Tally.BORN) }.sumOf { it.value - (before[it.key] ?: 0.0) }
            "%s %.0f / %.0f".format(s.name, born, died)
        })
    }

    /** This year's deaths of the resident herds, all kinds together, by cause. */
    fun printResidentDeathsThisYear() {
        val residentNames = sim.fauna.kinds.map { it.name }.toSet()
        val year = HashMap<String, Double>()
        for ((key, v) in causes) if (Tally.kindOf(key) in residentNames) year.merge(Tally.whatOf(key), v - (before[key] ?: 0.0), Double::plus)
        println("     resident deaths this year: " + year.entries.sortedByDescending { it.value }.filter { it.value >= 1.0 }
            .joinToString(", ") { "%s %.0f".format(it.key, it.value) })
    }

    /** Small against large herds: yearly deaths per animal, by cause. */
    fun printHerdDeathsBySize(stepsPerYear: Int) {
        val names = listOf("small (under a unit)", "middling (1-3 units)", "large (over 3 units)")
        println("herds' yearly deaths per animal by size (share of head-weeks): " + (0..2).joinToString("; ") { c ->
            val years = herdHeadWeeks[c] / stepsPerYear
            val keys = causes.keys.filter { it.startsWith(Tally.herdSize(c, "")) }
            "%s %.0f%%: ".format(names[c], 100 * herdHeadWeeks[c] / herdHeadWeeks.sum().coerceAtLeast(1.0)) +
                keys.sortedByDescending { causes[it] }.joinToString(", ") { k -> "%s %.3f".format(k.substringAfter(':'),
                    causes.getValue(k) / years.coerceAtLeast(1e-9)) }
        })
    }

    fun printRunDeaths() {
        println("deaths over the run, by cause:")
        for (s in sim.fauna.kinds + sim.groups.kinds) {
            val c = causes.entries.filter { Tally.kindOf(it.key) == s.name && !it.key.endsWith(Tally.BORN) }
                .map { Tally.whatOf(it.key) to it.value }.sortedByDescending { it.second }
            val all = c.sumOf { it.second }.coerceAtLeast(1.0)
            println("  %-12s ".format(s.name) + c.filter { 100 * it.second / all >= 0.5 }.joinToString(", ") { (k, v) -> "$k %.0f%%".format(100 * v / all) })
        }
    }
}

/** The hunting and feeding tallies the groups keep ([WeekStats.hunting]), by year; and the grain people reaped. */
class HuntTally(private val sim: HexSim) {
    /** This year's tallies, and the hunters' fed sums and group-weeks, by kind. */
    val year = TreeMap<String, Int>()
    private val fedSum = HashMap<String, Double>()
    private val groupWeeks = HashMap<String, Int>()
    /** The tallies of the last whole year, and of this one so far. */
    val lastYear = HashMap<String, Int>()
    private val now = HashMap<String, Int>()
    /** Grain reaped by people in the last whole year, and in this one so far, t. */
    var grainReapedLastYear = 0.0; private set
    private var grainReapedNow = 0.0

    /** For the kinds that live both ways, this year: head-weeks as herds and spread, and the residents' hunters' kills of each. */
    private val headWeeks = HashMap<String, Double>()
    private val residentsKilled = HashMap<String, Double>()
    /** The same: head-weeks times the residents' hunters' kg against all the plant-eaters' kg where each head is (see [printHerdSafety]). */
    private val pressureMet = HashMap<String, Double>()

    fun startYear() { year.clear(); fedSum.clear(); groupWeeks.clear(); headWeeks.clear(); residentsKilled.clear(); pressureMet.clear() }

    fun week() {
        val groups = sim.groups
        grainReapedNow += (groups.stats.hunting[Tally.of(Catalogue.HUMAN, WeekStats.GRAIN_REAPED)] ?: 0) / 1000.0
        for ((key, v) in groups.stats.hunting) year.merge(key, v, Int::plus)
        for ((key, v) in groups.stats.hunting) now.merge(key, v, Int::plus)
        for (g in groups.groups) if (g.alive && g.species in Catalogue.gathering) groupWeeks.merge("herds of ${g.species.name}", 1, Int::plus)
        val fauna = sim.fauna
        // (The residents' hunters' kg per kg of plant-eaters, residents and herds, at each place: what one head there
        // meets of them, as the hunters share their kills out by weight.)
        val land = sim.land; val n = land.n
        val pressure = DoubleArray(n)
        val herdsAt = groups.groundOfHerds()
        for (p in 0 until n) {
            var hunters = 0.0; var prey = 0.0
            for ((j, s) in fauna.kinds.withIndex()) {
                val kg = (fauna.adults[j][p] + 0.3 * fauna.young[j][p]) * s.massKg
                if (s in Catalogue.residentHunters) hunters += kg else if (!s.carnivore) prey += kg
            }
            herdsAt[p]?.let { for (at in it) prey += (at.g.adults + 0.3 * at.g.young) * at.share / land.areaKm2[p] * at.g.species.massKg }
            pressure[p] = if (prey > 0.0) hunters / prey else 0.0
        }
        for (s in Catalogue.gathering) {
            val k = fauna.slot(s); if (k < 0) continue
            var spread = 0.0
            for (p in 0 until n) spread += (fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p] * pressure[p]
            pressureMet.merge("${s.name} spread", spread, Double::plus)
        }
        for ((p, list) in herdsAt) for (at in list) if (at.g.alive && at.g.species in Catalogue.gathering)
            pressureMet.merge("${at.g.species.name} herds", at.g.size * at.share * pressure[p], Double::plus)
        for (s in Catalogue.gathering) {
            val k = fauna.slot(s); if (k < 0) continue
            headWeeks.merge("${s.name} herds", groups.count(s).toDouble(), Double::plus)
            headWeeks.merge("${s.name} spread", fauna.count(k), Double::plus)
            val inHerds = (groups.lostToResidents[s] ?: 0).toDouble()
            residentsKilled.merge("${s.name} herds", inHerds, Double::plus)
            residentsKilled.merge("${s.name} spread", (fauna.deaths[Tally.of(s, "resident hunters")] ?: 0.0) - inHerds, Double::plus)
        }
        for (g in groups.groups) if (g.alive && g.species.carnivore) {
            fedSum.merge(g.species.name, g.fed.toDouble(), Double::plus)
            groupWeeks.merge(g.species.name, 1, Int::plus)
        }
    }

    fun endYear() {
        grainReapedLastYear = grainReapedNow; grainReapedNow = 0.0
        lastYear.clear(); lastYear.putAll(now); now.clear()
    }

    /** How the hunting went, per hunting party per week. */
    fun printHunting() {
        println("     hunting, per party a week (chases / herds that saw it first / kills from herds / kills of residents / fed): " +
            sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gw = (groupWeeks[s.name] ?: 0).coerceAtLeast(1).toDouble()
            fun v(k: String) = (year[Tally.of(s, k)] ?: 0) / gw
            "%s %.2f / %.2f / %.2f / %.2f / %.2f".format(s.name, v(Tally.CHASES), v(Tally.SAW_FIRST), v(Tally.KILLS_FROM_HERDS),
                v(Tally.KILLS_OF_RESIDENTS), (fedSum[s.name] ?: 0.0) / gw)
        })
    }

    /** Each hunting kind's tries on herds this year, by herd kind, and how many hunters the prey killed. */
    fun printTriesOnHerds() {
        println("     hunters' tries on herds (kind: herd tries / hunters killed): " + sim.groups.kinds.filter { it.carnivore }.joinToString("; ") { s ->
            val prefix = Tally.of(s, Tally.TRIES_ON)
            s.name + ": " + year.entries.filter { it.key.startsWith(prefix) }.sortedByDescending { it.value }.joinToString(", ") { (k, n) ->
                val prey = k.removePrefix(prefix)
                "%s %d / %d".format(prey, n, year[Tally.of(s, Tally.HURT_BY + prey)] ?: 0)
            } + " (let pass: %d rare, %d not worth it)".format(year["${s.name}:herds let pass"] ?: 0, year["${s.name}:herds not worth it"] ?: 0)
        })
    }

    /** For the kinds that live both ways: herds gathered and scattered this year (heads), herd-weeks, and heads now in herds and spread. */
    fun printGathering() {
        val fauna = sim.fauna; val groups = sim.groups
        println("     gathering kinds (this year: herds gathered (heads) / scattered (heads), heads joined / settled on the way, herd-weeks; now: heads in herds / spread): " +
            Catalogue.gathering.filter { fauna.slot(it) >= 0 }.joinToString("; ") { s ->
                fun t(what: String) = year[Tally.of(s, what)] ?: 0
                "%s %d (%d) / %d (%d), %d / %d, %d; %d / %.0f".format(s.name, t(Tally.GATHERED), t(Tally.HEADS_GATHERED), t(Tally.SCATTERED), t(Tally.HEADS_SCATTERED),
                    t(Tally.HEADS_JOINED), t(Tally.HEADS_SETTLED),
                    groupWeeks["herds of ${s.name}"] ?: 0, groups.count(s), fauna.count(fauna.slot(s)))
            })
    }

    /**
     * For the kinds that live both ways: what the hunters took of them this year as herds and as residents, per head a
     * year (hunting groups + the residents' hunters), to set the safety a herd gives against living spread.
     */
    fun printHerdSafety() {
        val weeks = Date.DAYS_PER_YEAR / sim.cfg.daysPerStep.toDouble()
        println("     hunted per head a year, gathering kinds (in herds: by groups + by resident hunters / spread: the same): " +
            Catalogue.gathering.filter { sim.fauna.slot(it) >= 0 }.joinToString("; ") { s ->
                fun rate(form: String, groupKills: String): String {
                    val hw = (headWeeks["${s.name} $form"] ?: 0.0) / weeks
                    if (hw <= 0.0) return "-"
                    val byGroups = (year[Tally.of(s, groupKills)] ?: 0) / hw; val byResidents = (residentsKilled["${s.name} $form"] ?: 0.0) / hw
                    return "%.3f (%.3f + %.3f)".format(byGroups + byResidents, byGroups, byResidents)
                }
                "%s %s / %s".format(s.name, rate("herds", Tally.KILLED_IN_HERDS), rate("spread", Tally.KILLED_SPREAD))
            })
        println("     residents' hunters' kg per kg of plant-eaters where each head is, gathering kinds (in herds / spread): " +
            Catalogue.gathering.filter { sim.fauna.slot(it) >= 0 }.joinToString("; ") { s ->
                fun met(form: String): String { val hw = headWeeks["${s.name} $form"] ?: 0.0
                    return if (hw > 0.0) "%.4f".format((pressureMet["${s.name} $form"] ?: 0.0) / hw) else "-" }
                "%s %s / %s".format(s.name, met("herds"), met("spread"))
            })
        val fauna = sim.fauna
        println("     residents' hunters' take per kg in their size range, per hunt, gathering kinds (in herds / spread): " +
            Catalogue.gathering.filter { fauna.slot(it) >= 0 }.joinToString("; ") { s ->
                val h = fauna.huntSeen[fauna.slot(s)]
                fun r(kg: inflation.ecology.HexFauna.HuntSeen, taken: inflation.ecology.HexFauna.HuntSeen) =
                    if (h[kg.ordinal].sum() > 0.0) "%.4f".format(h[taken.ordinal].sum() / h[kg.ordinal].sum()) else "-"
                "%s %s / %s".format(s.name, r(inflation.ecology.HexFauna.HuntSeen.HERDS_KG, inflation.ecology.HexFauna.HuntSeen.HERDS_TAKEN),
                    r(inflation.ecology.HexFauna.HuntSeen.SPREAD_KG, inflation.ecology.HexFauna.HuntSeen.SPREAD_TAKEN))
            })
        for (row in fauna.huntSeen) for (a in row) a.reset()
        println("     young share now, gathering kinds (in herds / spread): " +
            Catalogue.gathering.filter { fauna.slot(it) >= 0 }.joinToString("; ") { s ->
                val k = fauna.slot(s)
                var hy = 0.0; var ha = 0.0
                for (g in sim.groups.groups) if (g.alive && g.species == s) { hy += g.young; ha += g.adults }
                var ry = 0.0; var ra = 0.0
                for (p in 0 until sim.land.n) { ry += fauna.young[k][p] * sim.land.areaKm2[p]; ra += fauna.adults[k][p] * sim.land.areaKm2[p] }
                fun sh(y: Double, a: Double) = if (y + a > 0.0) "%.2f".format(y / (y + a)) else "-"
                "%s %s / %s".format(s.name, sh(hy, ha), sh(ry, ra))
            })
    }

    /** Where people's hides came from this year: kg a person from herds, resident game and small pelts. */
    fun printPeoplesHides() {
        val heads = sim.groups.groups.filter { it.alive && it.species.human }.sumOf { it.size }.coerceAtLeast(1)
        fun kg(source: String) = (year[Tally.hide(source)] ?: 0) / 1000.0 / heads
        println("     people's hides (kg a person this year: herds / resident game / small pelts): %.2f / %.2f / %.2f"
            .format(kg("herds"), kg("residents"), kg("small pelts")))
    }

    /**
     * What each hunting kind's diet asked for and what each part brought, as shares of what it asked (what each eats
     * comes from what pays best; meat includes fish).
     */
    fun printHuntersFood() {
        println("     hunters' food (meat / small prey / carrion / plants, as shares of what they need): " +
            sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val asked = (year[Tally.diet(s, "asked")] ?: 0).toDouble().coerceAtLeast(1.0)
            fun part(k: String) = 100 * (year[Tally.diet(s, k)] ?: 0) / asked
            "%s %.0f/%.0f/%.0f/%.0f%%".format(s.name, part("meat"), part("small prey"), part("carrion"), part("plants"))
        })
    }
}

/** How far groups go: km moved this year by kind, and where each herd is in January against July. */
class MovementTally(private val sim: HexSim) {
    val movedKm = HashMap<Species, Double>()
    private val janHex = HashMap<Int, Int>()
    private val julyKm = HashMap<String, ArrayList<Double>>()
    /** Head-weeks of each herd kind (and of people, under "people") by month and [CELL_KM] cell, this year and last. */
    private var cells = HashMap<String, Array<HashMap<Long, Double>>>()
    private var cellsLastYear = HashMap<String, Array<HashMap<Long, Double>>>()
    /** Each resident kind's heads by hex in the year's first week, and its heads-weighted mean height then (m). */
    private var janResidents: Array<DoubleArray>? = null
    private var janHeight = DoubleArray(0)
    /** Each resident kind's January to July: the share of its heads in other hexes, in other [CELL_KM] cells, and its mean heights. */
    private val residentsMoved = HashMap<String, DoubleArray>()

    fun startYear() { julyKm.clear(); cellsLastYear = cells; cells = HashMap(); residentsMoved.clear() }

    fun week(w: Int) {
        val groups = sim.groups; val hw = sim.hw; val hex = sim.hex
        if (w == 0) movedKm.clear()
        for (gr in groups.groups) if (gr.alive && gr.path.size > 1) movedKm.merge(gr.species, (gr.path.size - 1) * hex.hexKm, Double::plus)
        val date = sim.weather.date
        for (g in groups.groups) if (g.alive && (!g.species.carnivore || g.species.human)) {
            val (x, y) = hex.centreKm(hw.placeHex[g.place])
            val cell = (x / CELL_KM).toLong() * 100_000 + (y / CELL_KM).toLong()
            cells.getOrPut(g.species.name) { Array(12) { HashMap() } }[date.month].merge(cell, g.size.toDouble(), Double::plus)
        }
        if (date.dayOfMonth <= sim.cfg.daysPerStep) {
            if (date.month == 0) for (g in groups.groups) if (g.alive && !g.species.carnivore) janHex[g.id] = hw.placeHex[g.place]
            if (date.month == 6) for (g in groups.groups) if (g.alive && !g.species.carnivore) janHex[g.id]?.let {
                julyKm.getOrPut(g.species.name) { ArrayList() } += hex.distanceKm(it, hw.placeHex[g.place])
            }
            if (date.month == 0) residentsByHex().let { (byHex, height) -> janResidents = byHex; janHeight = height }
            if (date.month == 6) janResidents?.let { jan -> compareResidents(jan, residentsByHex()) }
        }
    }

    /** Each resident kind's heads by hex, and its heads-weighted mean height (m). */
    private fun residentsByHex(): Pair<Array<DoubleArray>, DoubleArray> {
        val fauna = sim.fauna; val hw = sim.hw; val land = sim.land
        val height = DoubleArray(fauna.kinds.size)
        val byHex = Array(fauna.kinds.size) { k ->
            val out = DoubleArray(sim.hex.size); var m = 0.0; var heads = 0.0
            for (p in 0 until fauna.n) {
                val v = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                out[hw.placeHex[p]] += v; m += v * hw.placeElevationM[p]; heads += v
            }
            height[k] = if (heads > 0.0) m / heads else 0.0
            out
        }
        return byHex to height
    }

    private fun compareResidents(jan: Array<DoubleArray>, now: Pair<Array<DoubleArray>, DoubleArray>) {
        val hex = sim.hex
        for ((k, s) in sim.fauna.kinds.withIndex()) {
            val a = jan[k]; val b = now.first[k]
            val ta = a.sum(); val tb = b.sum()
            if (ta <= 0.0 || tb <= 0.0) continue
            var byHex = 0.0
            val cellA = HashMap<Long, Double>(); val cellB = HashMap<Long, Double>()
            for (h in a.indices) {
                if (a[h] <= 0.0 && b[h] <= 0.0) continue
                byHex += kotlin.math.abs(a[h] / ta - b[h] / tb)
                val (x, y) = hex.centreKm(h)
                val cell = (x / CELL_KM).toLong() * 100_000 + (y / CELL_KM).toLong()
                cellA.merge(cell, a[h] / ta, Double::plus); cellB.merge(cell, b[h] / tb, Double::plus)
            }
            val byCell = (cellA.keys + cellB.keys).sumOf { kotlin.math.abs((cellA[it] ?: 0.0) - (cellB[it] ?: 0.0)) }
            residentsMoved[s.name] = doubleArrayOf(byHex / 2, byCell / 2, janHeight[k], now.second[k])
        }
    }

    /** How far the herds are in July from where they were in January, and how far apart the places they remember for the two are. */
    fun printSeasons() {
        val groups = sim.groups; val hw = sim.hw
        if (julyKm.isNotEmpty()) println("     herds' January to July (mean km / share over 50 km): " + julyKm.entries.joinToString(", ") { (k, v) ->
            "%s %.0f / %.0f%%".format(k, v.average(), 100.0 * v.count { it > 50.0 } / v.size) })
        println("     herds' remembered January and July places apart (mean km): " + groups.kinds.filter { !it.carnivore }.joinToString(", ") { s ->
            val d = groups.groups.filter { it.alive && it.species == s && it.seasonPlace[0] >= 0 && it.seasonPlace[6] >= 0 }
                .map { sim.hex.distanceKm(hw.placeHex[it.seasonPlace[0]], hw.placeHex[it.seasonPlace[6]]) }
            "%s %.0f".format(s.name, if (d.isEmpty()) 0.0 else d.average())
        })
        if (residentsMoved.isNotEmpty()) println("     residents' January to July (share of heads in other hexes / other ${CELL_KM.toInt()} km cells, mean height m): " +
            residentsMoved.entries.joinToString(", ") { (k, v) -> "%s %.0f%% / %.0f%%, %.0f to %.0f".format(k, 100 * v[0], 100 * v[1], v[2], v[3]) })
        printPredictable()
    }

    /**
     * How well last year foretells where each herd kind is this year, by [CELL_KM] cells: the correlation of its
     * head-weeks over the cells in the same month of the two years (the mean over months), against the same for
     * the month six months off (what knowing only where the kind lives would give); and the share of people's
     * head-weeks in cells where a herd stood that month.
     */
    private fun printPredictable() {
        if (cellsLastYear.isEmpty()) return
        val kinds = cells.keys.filter { it != "people" && it in cellsLastYear }.sorted()
        println("     herds foretold by last year (r same month / r six months off, by ${CELL_KM.toInt()} km cells): " + kinds.joinToString(", ") { k ->
            val now = cells.getValue(k); val before = cellsLastYear.getValue(k)
            "%s %.2f / %.2f".format(k, (0 until 12).map { correlation(now[it], before[it]) }.average(),
                (0 until 12).map { correlation(now[it], before[(it + 6) % 12]) }.average())
        })
        val people = cells["people"] ?: return
        val herded = (0 until 12).sumOf { m -> people[m].entries.sumOf { (c, v) -> if (kinds.any { (cells.getValue(it)[m][c] ?: 0.0) > 0.0 }) v else 0.0 } }
        val all = (0 until 12).sumOf { m -> people[m].values.sum() }.coerceAtLeast(1.0)
        val cellsWithHerds = (0 until 12).sumOf { m -> kinds.flatMap { cells.getValue(it)[m].keys }.toSet().size }
        val cellsWithAny = (0 until 12).sumOf { m -> (kinds.flatMap { cells.getValue(it)[m].keys } + people[m].keys).toSet().size }.coerceAtLeast(1)
        println("     people where herds were that month: %.0f%% of their head-weeks (herds in %.0f%% of the cells either used)"
            .format(100 * herded / all, 100.0 * cellsWithHerds / cellsWithAny))
    }

    private fun correlation(a: Map<Long, Double>, b: Map<Long, Double>): Double {
        val keys = (a.keys + b.keys).toList()
        if (keys.size < 3) return 0.0
        val x = keys.map { a[it] ?: 0.0 }; val y = keys.map { b[it] ?: 0.0 }
        val mx = x.average(); val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val sx = x.sumOf { (it - mx) * (it - mx) }; val sy = y.sumOf { (it - my) * (it - my) }
        return if (sx > 0 && sy > 0) sxy / kotlin.math.sqrt(sx * sy) else 0.0
    }

    companion object {
        /** Cell size for where herds are, km: about a band's reach in a few days. */
        const val CELL_KM = 50.0
    }
}

/** People week by week, for the bands' line and the comparison with the ethnographic record. */
class PeopleTally(private val sim: HexSim, private val stepsPerYear: Int) {
    private var bandWeeks = 0.0
    private var bandHeads = 0.0
    private var childWeeks = 0.0
    private var adultWeeks = 0.0
    private var motherWeeks = 0.0
    private var moves = 0
    /** Hexes each band walked this year, and its last camp. */
    private val walked = HashMap<Int, HashSet<Int>>(); private val lastCamp = HashMap<Int, Int>()
    /** Camp moves and band-weeks since last read, of bands without houses [0] and with [1]. */
    private val movesHouse = LongArray(2); private val bandWeeksHouse = LongArray(2)
    /** Appointments due and kept since last read: of bands in their appointment's month, how many are at its hex. */
    private var apptDue = 0L; private var apptKept = 0L

    fun startYear() {
        bandWeeks = 0.0; bandHeads = 0.0; childWeeks = 0.0; adultWeeks = 0.0; motherWeeks = 0.0; moves = 0
        walked.clear(); lastCamp.clear()
    }

    fun week() {
        val groups = sim.groups; val hw = sim.hw
        for (g in groups.groups) {
            val b = g.band ?: continue
            if (!g.alive) continue
            val i = if (b.house >= Thresholds.HOUSE_STANDING) 1 else 0
            bandWeeksHouse[i]++
            if (b.campWeeks == 0) movesHouse[i]++
        }
        for (g in groups.groups) {
            val b = g.band ?: continue
            val ap = b.appointment
            if (!g.alive || ap == null || ap.month != sim.weather.date.month) continue
            apptDue++
            if (hw.placeHex[g.place] == ap.hex.i) apptKept++
        }
        for (g in groups.groups) if (g.alive && g.band != null) {
            bandWeeks++; bandHeads += g.size; childWeeks += g.young; adultWeeks += g.adults; motherWeeks += (g.adults - g.band!!.elders) / 2
            val h = hw.placeHex[g.place]
            for (q in g.path) walked.getOrPut(g.id) { HashSet() } += q
            lastCamp.put(g.id, h)?.let { if (it != h) moves++ }
        }
    }

    /** Camp moves a year of bands with houses or without, since last read. */
    private fun movesWith(houses: Boolean): Double {
        val i = if (houses) 1 else 0
        val v = movesHouse[i] * 52.0 / bandWeeksHouse[i].coerceAtLeast(1)
        movesHouse[i] = 0
        bandWeeksHouse[i] = 0
        return v
    }
    private fun keptShare(): Double { val v = 100.0 * apptKept / apptDue.coerceAtLeast(1); apptDue = 0; apptKept = 0; return v }
    private fun headsOf(l: List<HexGroup>): Double = l.sumOf { it.size.toDouble() }.coerceAtLeast(1.0)

    /** Bands: their skills, the tools they know and how full their kit is, how long they keep camp, their larders, houses, goods, ties. */
    fun printBands() {
        val bands = sim.groups.groups.filter { it.alive && it.band != null }
        if (bands.isEmpty()) return
        val bs = bands.map { it.band!! }
        println("     bands: " + skillsAndKit(bands, bs) + houses(bands, bs) + goods(bands, bs) + gatherings(bands, bs))
    }

    private fun skillsAndKit(bands: List<HexGroup>, bs: List<Band>): String {
        val groups = sim.groups; val hex = sim.hex
        return "skills " + Skill.entries.joinToString(", ") { f -> "%s %.2f".format(f.name.lowercase(), bs.map { it.skills[f] }.average()) } +
            " | tools known (kit) " + Tool.entries.joinToString(", ") { t -> "%s %.0f%% (%.2f)".format(t.name.lowercase(),
                100.0 * bs.count { it.knows[t] } / bs.size, bs.map { it.kit[t] }.average()) } +
            " | customs " + Tradition.entries.joinToString(", ") { t -> "%s %.0f%%".format(t.name.lowercase(), 100.0 * bs.count { it.holds(t) } / bs.size) } +
            " | elders %.0f%% of adults, children inbred %.3f".format(100.0 * bs.sumOf { it.elders } / bands.sumOf { it.adults }.coerceAtLeast(1),
                bs.map { it.inbred.toDouble() }.average()) +
            " | caches %d".format(bs.count { !it.cachePlace.none }) +
            " | dogs %.0f in %d bands, wolf packs' boldness %.2f".format(bs.sumOf { it.kept.sum().toDouble() }, bs.count { it.kept.sum() >= 1f },
                groups.groups.filter { it.alive && it.species.followsPeople && !it.species.human }.map { boldness(it).toDouble() }.average()) +
            " | country known %.0f km²".format(bs.map { b -> b.known.sumOf { java.lang.Long.bitCount(it) }.toDouble() }.average() * hex.areaKm2) +
            " | camp weeks %.1f, larder days %.0f".format(bs.map { it.campWeeks }.average(),
                bands.map { it.larder / it.dailyNeedKg.coerceAtLeast(1e-3f) }.average())
    }

    /**
     * Houses, camp moves with and without them (clearing those counts), and houses by the rain where bands are (the
     * mobile test wants them rarer on dry, poor land).
     */
    private fun houses(bands: List<HexGroup>, bs: List<Band>): String {
        val hw = sim.hw
        return " | houses: %.0f%% of bands have them (%.0f%% at home), camps moved a year %.1f with houses vs %.1f without".format(
                100.0 * bs.count { it.house >= Thresholds.HOUSE_STANDING } / bs.size.coerceAtLeast(1),
                100.0 * bs.count { it.atHome } / bs.size.coerceAtLeast(1),
                movesWith(true), movesWith(false)) +
            " | with houses by yearly rain: " + listOf(0f to 300f, 300f to 600f, 600f to 1e9f).joinToString(", ") { (lo, hi) ->
                val inRain = bands.filter { val h = hw.placeHex[it.place]; val r = (0 until 12).sumOf { m -> hw.precip[m][h].toDouble() }; r >= lo && r < hi }
                "%s %.0f%% of %d".format(if (hi > 1e8f) ">600 mm" else "%.0f-%.0f mm".format(lo, hi),
                    100.0 * inRain.count { it.band!!.house >= Thresholds.HOUSE_STANDING } / inRain.size.coerceAtLeast(1), inRain.size) }
    }

    /** Kept herds, ornaments and shell, grave goods. */
    private fun goods(bands: List<HexGroup>, bs: List<Band>): String {
        val hw = sim.hw; val land = sim.land; val fauna = sim.fauna
        return " | kept herds: " + fauna.kinds.indices.filter { k -> bs.any { it.herd.size > k && it.herd[k] >= 1f } }.joinToString(", ") { k ->
                val keepers = bs.filter { it.herd.size > k && it.herd[k] >= 1f }
                "%s %.0f head in %d bands (tame %.2f)".format(fauna.kinds[k].name, keepers.sumOf { it.herd[k].toDouble() }, keepers.size,
                    keepers.map { it.herdTame[k].toDouble() }.average()) }.ifEmpty { "none" } +
            " | ornaments %.1f a person (%.0f%% of shell), shell beads a person inland %.2f, by the sea %.2f; graves with goods %d (richest hold %.0f%% of goods)".format(
                bs.sumOf { it.ornaments.toDouble() } / headsOf(bands),
                100.0 * bs.sumOf { it.exotic.toDouble() } / bs.sumOf { it.ornaments.toDouble() }.coerceAtLeast(1e-9),
                bands.filter { !hw.coast[hw.placeHex[it.place]] }.let { l -> l.sumOf { it.band!!.exotic.toDouble() } / headsOf(l) },
                bands.filter { hw.coast[hw.placeHex[it.place]] }.let { l -> l.sumOf { it.band!!.exotic.toDouble() } / headsOf(l) },
                land.graveGoods.count { it >= 1f },
                land.graveGoods.sortedDescending().let { gs ->
                    100.0 * gs.take((gs.count { it >= 1f } / 10).coerceAtLeast(1)).sum() / gs.sum().coerceAtLeast(1e-9f) })
    }

    /**
     * Raids and palisades, appointments (clearing those counts), burial places, and ties and gatherings: bands camped
     * with two or more others in the same hex.
     */
    private fun gatherings(bands: List<HexGroup>, bs: List<Band>): String {
        val hw = sim.hw; val land = sim.land
        return " | raids on homes, palisades %.0f%% of bands, homes known %.1f a band".format(
                100.0 * bs.count { it.palisade >= 0.2f } / bs.size.coerceAtLeast(1), bs.map { it.knownHomes.size }.average()) +
            " | appointments %.0f%% of bands, kept %.0f%% of band-weeks due this year; burial places %d; largest gathering %d bands".format(
                100.0 * bs.count { it.appointment != null } / bs.size.coerceAtLeast(1),
                keptShare(),
                land.burials.count { it >= 1f }, bands.groupBy { hw.placeHex[it.place] }.values.maxOfOrNull { it.size } ?: 0) +
            " | ties %.1f a band (close %.1f), gathered %.0f%%".format(bs.map { it.ties.size }.average(),
                bs.map { b -> b.ties.values.count { it > 0.3f } }.average(),
                100.0 * bands.groupBy { hw.placeHex[it.place] }.values.filter { it.size >= 3 }.sumOf { it.size } / bands.size.coerceAtLeast(1))
    }

    /** Children's deaths by cause, and people against what the ethnographic and archaeological record shows of foragers (ranges in brackets). */
    fun printAgainstTheRecord(deaths: DeathTally, hunting: HuntTally) {
        if (bandWeeks <= 0) return
        val weeks = stepsPerYear.toDouble()
        fun yearly(cause: String) = deaths.thisYear(Tally.of(Catalogue.HUMAN, cause))
        val causes = deaths.causes
        val died = causes.keys.filter { it.startsWith(Tally.of(Catalogue.HUMAN, "")) && !it.endsWith(Tally.BORN) && !it.endsWith(":" + Tally.HUNTED) }.sumOf { yearly(Tally.whatOf(it)) }
        val childDied = (hunting.year[Tally.of(Catalogue.HUMAN, WeekStats.CHILDREN_DIED)] ?: 0).toDouble()
        val children = childWeeks / weeks; val adults = adultWeeks / weeks
        val childQ = childDied / children.coerceAtLeast(1.0)
        val adultQ = ((died - childDied).coerceAtLeast(0.0)) / adults.coerceAtLeast(1.0)
        val meat = (hunting.year[Tally.of(Catalogue.HUMAN, WeekStats.MEAT_EATEN)] ?: 0).toDouble()
        val plants = (hunting.year[Tally.of(Catalogue.HUMAN, WeekStats.PLANTS_EATEN)] ?: 0).toDouble()
        val violent = yearly(DeathCause.RAIDS.label) +
            causes.keys.filter { it.startsWith(Tally.of(Catalogue.HUMAN, DeathCause.KILLED_BY)) }.sumOf { yearly(Tally.whatOf(it)) }
        val bandsSeen = walked.size.coerceAtLeast(1)
        run {
            val all = sim.groups.stats.childDeaths.mapValues { it.value.sumThenReset() }.filter { it.value > 0.0 }
            val total = all.values.sum().coerceAtLeast(1e-9)
            println("     children's deaths by cause this year (expected from the odds, share): " + all.entries.sortedByDescending { it.value }
                .joinToString(", ") { "%s %.0f%%".format(it.key, 100 * it.value / total) })
        }
        val hex = sim.hex
        println("     people against the record: band size %.0f [15-50, most 20-35] | people per km² %.3f [foragers 0.01-0.4; Mediterranean 0.05-0.2] | camps moved a year %.1f [5-60, median about 15] | ground walked a year %.0f km² [200-2500] | plant share of food %.0f%% [Mediterranean 40-60%%] | years between births %.1f [3-4] | children reaching 15 %.0f%% [about 55-65%%] | years left at 15 %.0f [about 35-40] | violent deaths %.0f%% [2-15%%]".format(
            bandHeads / bandWeeks, bandHeads / weeks / sim.land.areaKm2.sum(), moves.toDouble() / bandsSeen,
            walked.values.map { it.size }.average() * hex.areaKm2, 100 * plants / (meat + plants).coerceAtLeast(1.0),
            (motherWeeks / weeks) / yearly(Tally.BORN).coerceAtLeast(1.0), 100 * Math.pow((1 - childQ).coerceIn(0.0, 1.0), 15.0),
            1 / adultQ.coerceAtLeast(1e-3), 100 * violent / died.coerceAtLeast(1.0)))
    }
}

/** How much small life swings from year to year in one place (not the map's total, where out-of-step places smooth each other out). */
class LocalSwings(private val sim: HexSim, private val stepsPerYear: Int) {
    /** Sample places: every so many places with some plant cover. */
    private val places = sim.land.let { land ->
        (0 until land.n step (land.n / 60).coerceAtLeast(1)).filter { land.plants.herb[it] + land.plants.shrub[it] > 0.05f }.take(40) }
    private val sum = Array(places.size) { FloatArray(5) }
    /** Each place's yearly means: rodents, hares, foxes, owls, game birds. */
    private val years = Array(places.size) { ArrayList<FloatArray>() }
    private var weeks = 0

    fun week() {
        val sl = sim.fauna.small
        for ((j, sp) in places.withIndex()) {
            sum[j][0] += sl.rodents[sp]
            sum[j][1] += sl.hares[sp]
            sum[j][2] += sl.foxes[sp]
            sum[j][3] += sl.owls[sp]
            sum[j][4] += sl.birds[sp]
        }
        weeks++
        if (weeks == stepsPerYear) { for (j in places.indices) { years[j] += sum[j].map { it / weeks }.toFloatArray(); sum[j].fill(0f) }; weeks = 0 }
    }

    fun print() {
        val names = listOf("rodents", "hares", "foxes", "owls", "game birds")
        println("local swings at %d places (median over places of the largest year against the smallest / share of place-years with none): "
            .format(places.size) + names.indices.joinToString(", ") { c ->
            val ratios = years.mapNotNull { ys ->
                val v = ys.drop(1).map { it[c] }
                val mx = v.maxOrNull() ?: 0f
                val mn = v.minOrNull() ?: 0f
                if (mx > 0f) (if (mn > 0f) mx / mn else Float.POSITIVE_INFINITY) else null
            }.sorted()
            val empty = years.sumOf { ys -> ys.drop(1).count { it[c] <= 0.001f } }.toDouble() / years.sumOf { (it.size - 1).coerceAtLeast(0) }.coerceAtLeast(1)
            "%s %.1fx / %.0f%%".format(names[c], if (ratios.isEmpty()) 0f else ratios[ratios.size / 2], 100 * empty)
        })
    }
}
