package inflation.tools.fauna

import inflation.world.Hex
import inflation.ecology.set
import inflation.ecology.get
import inflation.ecology.ADULT_YEARS
import inflation.ecology.Tally
import inflation.ecology.ByBeast
import inflation.ecology.Catalogue
import inflation.ecology.TallyKey
import inflation.ecology.Thresholds
import inflation.ecology.DeathCause
import inflation.ecology.WeekStats
import inflation.ecology.Band
import inflation.ecology.Built
import inflation.ecology.FoodFrom
import inflation.ecology.HerdFlow
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.ecology.Species
import inflation.ecology.Tool
import inflation.ecology.Material
import inflation.ecology.Tradition
import inflation.ecology.bandLifeway
import inflation.ecology.boldness
import inflation.ecology.houseFoodAYear
import inflation.ecology.houseLivesAYear
import java.util.TreeMap
import inflation.world.get
import inflation.world.set
import inflation.world.Place
import inflation.world.Date

/** Deaths and births by "kind:cause" over the run, from the model's weekly ledgers. */
class DeathTally(private val sim: HexSim) {
    /** Deaths over the run by "kind:cause" (births as "kind:+born"). */
    val causes = TreeMap<TallyKey, Double>()
    /** [causes] as it stood when this year began. */
    private var before: Map<TallyKey, Double> = emptyMap()

    fun startYear() { before = causes.toMap() }

    fun week() {
        val fauna = sim.fauna; val groups = sim.groups
        for ((key, v) in fauna.deaths) causes.merge(key, v, Double::plus)
        for ((key, v) in groups.stats.deaths) causes.merge(key, v.toDouble(), Double::plus)
        for ((s, v) in groups.stats.kills) causes.merge(Tally.of(s.name, Tally.HUNTED), v.toDouble(), Double::plus)
        for ((s, v) in groups.stats.births) causes.merge(Tally.of(s.name, Tally.BORN), v.toDouble(), Double::plus)
    }

    /** What [key] came to this year. */
    fun thisYear(key: TallyKey) = (causes[key] ?: 0.0) - (before[key] ?: 0.0)

    fun printHuntersBornAndDied() {
        println("     hunters this year, born / died: " + sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val born = thisYear(Tally.of(s.name, Tally.BORN))
            val died = causes.entries.filter { it.key.startsWith(Tally.of(s)) && !it.key.text.endsWith(Tally.BORN) }.sumOf { it.value - (before[it.key] ?: 0.0) }
            "%s %.0f / %.0f".format(s.name, born, died)
        })
    }

    /** This year's deaths of the residents, all kinds together, by cause. */
    fun printResidentDeathsThisYear() {
        val residentNames = sim.fauna.kinds.map { it.name }.toSet()
        val year = HashMap<String, Double>()
        for ((key, v) in causes) if (Tally.kindOf(key) in residentNames && Tally.whatOf(key) != Tally.BORN) year.merge(Tally.whatOf(key), v - (before[key] ?: 0.0), Double::plus)
        println("     resident deaths this year: " + year.entries.sortedByDescending { it.value }.filter { it.value >= 1.0 }
            .joinToString(", ") { "%s %.0f".format(it.key, it.value) })
    }

    fun printRunDeaths() {
        println("deaths over the run, by cause:")
        for (s in sim.fauna.kinds + sim.groups.kinds) {
            val c = causes.entries.filter { Tally.kindOf(it.key) == s.name && !it.key.text.endsWith(Tally.BORN) }
                .map { Tally.whatOf(it.key) to it.value }.sortedByDescending { it.second }
            val all = c.sumOf { it.second }.coerceAtLeast(1.0)
            println("  %-12s ".format(s.name) + c.filter { 100 * it.second / all >= 0.5 }.joinToString(", ") { (k, v) -> "$k %.0f%%".format(100 * v / all) })
        }
    }
}

/** The hunting and feeding tallies the groups keep ([WeekStats.hunting]), by year; and the grain people reaped. */
class HuntTally(private val sim: HexSim) {
    /** This year's tallies, and the hunters' fed sums and group-weeks, by kind. */
    val year = TreeMap<TallyKey, Int>()
    private val fedSum = HashMap<String, Double>()
    private val groupWeeks = HashMap<String, Int>()
    /** The tallies of the last whole year, and of this one so far. */
    val lastYear = HashMap<TallyKey, Int>()
    private val now = HashMap<TallyKey, Int>()
    /** Grain reaped by people in the last whole year, and in this one so far, t. */
    var grainReapedLastYear = 0.0; private set
    private var grainReapedNow = 0.0

    fun startYear() { year.clear(); fedSum.clear(); groupWeeks.clear() }

    fun week() {
        val groups = sim.groups
        grainReapedNow += (groups.stats.hunting[Tally.of(Catalogue.HUMAN, Tally.OfKind.GRAIN_REAPED)] ?: 0) / 1000.0
        for ((key, v) in groups.stats.hunting) year.merge(key, v, Int::plus)
        for ((key, v) in groups.stats.hunting) now.merge(key, v, Int::plus)
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
        println("     hunting, per party a week (kills of residents / fed): " +
            sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val gw = (groupWeeks[s.name] ?: 0).coerceAtLeast(1).toDouble()
            "%s %.2f / %.2f".format(s.name, (year[Tally.of(s, Tally.OfKind.KILLS_OF_RESIDENTS)] ?: 0) / gw, (fedSum[s.name] ?: 0.0) / gw)
        })
    }

    /** Where people's hides came from this year: kg a person from resident game and small pelts. */
    fun printPeoplesHides() {
        val heads = sim.groups.groups.filter { it.alive && it.species.human }.sumOf { it.size }.coerceAtLeast(1)
        fun kg(source: Tally.HideSource) = (year[Tally.hide(source)] ?: 0) / 1000.0 / heads
        println("     people's hides (kg a person this year: resident game / small pelts): %.2f / %.2f"
            .format(kg(Tally.HideSource.RESIDENTS), kg(Tally.HideSource.SMALL_PELTS)))
    }

    /**
     * What each hunting kind's diet asked for and what each part brought, as shares of what it asked (what each eats
     * comes from what pays best; meat includes fish).
     */
    fun printHuntersFood() {
        println("     hunters' food (meat / small prey / carrion / plants, as shares of what they need): " +
            sim.groups.kinds.filter { it.carnivore }.joinToString(", ") { s ->
            val asked = (year[Tally.diet(s, Tally.DietPart.ASKED)] ?: 0).toDouble().coerceAtLeast(1.0)
            fun part(k: Tally.DietPart) = 100.0 * (year[Tally.diet(s, k)] ?: 0) / asked
            "%s %.0f/%.0f/%.0f/%.0f%%".format(s.name, part(Tally.DietPart.MEAT), part(Tally.DietPart.SMALL_PREY), part(Tally.DietPart.CARRION), part(Tally.DietPart.PLANTS))
        })
    }
}

/** How far groups go: km moved this year by kind; and where each resident kind is in January against July. */
class MovementTally(private val sim: HexSim) {
    val movedKm = HashMap<Species, Double>()
    /** Each resident kind's heads by hex in the year's first week, and its heads-weighted mean height then (m). */
    private var janResidents: Array<DoubleArray>? = null
    private var janHeight = DoubleArray(0)
    /** Each resident kind's January to July: the share of its heads in other hexes, in other [CELL_KM] cells, and its mean heights. */
    private val residentsMoved = HashMap<String, DoubleArray>()
    /**
     * Each resident kind's adults' heads-weighted mean height (m) in each month's first week. Adults only: the young born in
     * spring, more on some ground than other, would move the mean without any animal moving.
     */
    private var adultHeight = Array(sim.fauna.kinds.size) { DoubleArray(12) { Double.NaN } }

    /** Each resident kind's heads in each month's first week, for the height the flow took them per head ([HexFauna.climbed]). */
    private var monthHeads = Array(sim.fauna.kinds.size) { DoubleArray(12) }

    fun startYear() {
        residentsMoved.clear(); adultHeight = Array(sim.fauna.kinds.size) { DoubleArray(12) { Double.NaN } }
        monthHeads = Array(sim.fauna.kinds.size) { DoubleArray(12) }; for (c in sim.fauna.climbed) c.fill(0.0)
    }

    fun week(w: Int) {
        val groups = sim.groups; val hex = sim.hex
        if (w == 0) movedKm.clear()
        for (gr in groups.groups) if (gr.alive && gr.path.size > 1) movedKm.merge(gr.species, (gr.path.size - 1) * hex.hexKm, Double::plus)
        val date = sim.weather.date
        if (date.dayOfMonth <= sim.cfg.daysPerStep) {
            if (date.month == 0) residentsByHex().let { (byHex, height) -> janResidents = byHex; janHeight = height }
            if (date.month == 6) janResidents?.let { jan -> compareResidents(jan, residentsByHex()) }
            adultHeights(date.month)
        }
    }

    private fun adultHeights(month: Int) {
        val fauna = sim.fauna; val hw = sim.hw; val land = sim.land
        for (k in fauna.kinds.indices) {
            var m = 0.0; var heads = 0.0
            var all = 0.0
            for (p in 0 until fauna.n) {
                val v = (fauna.adults[k][p] * land.areaKm2[p]).toDouble(); m += v * hw.placeElevationM[p]; heads += v
                all += v + fauna.young[k][p] * land.areaKm2[p]
            }
            adultHeight[k][month] = if (heads > 0.0) m / heads else Double.NaN
            monthHeads[k][month] = all
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
                val (x, y) = hex.centreKm(Hex(h))
                val cell = (x / CELL_KM).toLong() * 100_000 + (y / CELL_KM).toLong()
                cellA.merge(cell, a[h] / ta, Double::plus); cellB.merge(cell, b[h] / tb, Double::plus)
            }
            val byCell = (cellA.keys + cellB.keys).sumOf { kotlin.math.abs((cellA[it] ?: 0.0) - (cellB[it] ?: 0.0)) }
            residentsMoved[s.name] = doubleArrayOf(byHex / 2, byCell / 2, janHeight[k], now.second[k])
        }
    }

    /** How far the residents are in July from where they were in January. */
    fun printSeasons() {
        if (residentsMoved.isNotEmpty()) println("     residents' January to July (share of heads in other hexes / other ${CELL_KM.toInt()} km cells, mean height m): " +
            residentsMoved.entries.joinToString(", ") { (k, v) -> "%s %.0f%% / %.0f%%, %.0f to %.0f".format(k, 100 * v[0], 100 * v[1], v[2], v[3]) })
        if (adultHeight.any { h -> h.any { !it.isNaN() } }) println("     residents' adults' mean height by month, January to December (m): " +
            sim.fauna.kinds.indices.filter { k -> adultHeight[k].any { !it.isNaN() } }.joinToString("; ") { k ->
                sim.fauna.kinds[k].name + " " + adultHeight[k].joinToString(" ") { if (it.isNaN()) "-" else "%.0f".format(it) } })
        val climbed = sim.fauna.climbed
        if (climbed.any { c -> c.any { it != 0.0 } }) println("     residents' climb by their moving, January to December (m a head in the month): " +
            sim.fauna.kinds.indices.filter { k -> climbed[k].any { it != 0.0 } }.joinToString("; ") { k ->
                sim.fauna.kinds[k].name + " " + (0 until 12).joinToString(" ") { m -> val h = monthHeads[k][m]
                    if (h > 0.0) "%+.0f".format(climbed[k][m] / h) else "-" } })
    }

    companion object {
        /** Cell size for where residents are, km: about a band's reach in a few days. */
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
    private val walked = HashMap<Int, HashSet<Hex>>(); private val lastCamp = HashMap<Int, Hex>()
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
            if (hw.placeHex[g.place] == ap.hex) apptKept++
        }
        for (g in groups.groups) if (g.alive && g.band != null) {
            bandWeeks++; bandHeads += g.size; childWeeks += g.young; adultWeeks += g.adults; motherWeeks += (g.adults - g.band!!.elders) / 2.0
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
        println("     bands by fish within reach of their camp, thirds from least to most: " + fishing(bands))
    }

    /**
     * Bands in thirds by the fish within reach of their camps ([HexWater.fishWithin] at full skill, kg a week): the fish, their
     * skills at fishing, small game and hunting, their nets, where their food came from over about a year ([Band.foodFrom],
     * kg a person a day), and how fish paid against the least their gatherers took up ([Band.fishPull]). Whether bands where
     * fish is plenty specialise in it.
     */
    private fun fishing(bands: List<HexGroup>): String {
        val water = sim.groups.fauna.water; val hw = sim.hw
        val sorted = bands.mapNotNull { g -> g.band?.let { b -> water.fishWithin(hw.placeHex[g.place]) to b } }.sortedBy { it.first }
        val n = sorted.size
        return (0 until 3).joinToString(" | ") { k ->
            val third = sorted.subList(k * n / 3, (k + 1) * n / 3)
            if (third.isEmpty()) "none" else {
                fun mean(f: (Band) -> Float) = third.map { f(it.second).toDouble() }.average()
                "fish %.0f kg/wk: skill fish %.2f, small game %.2f, hunt %.2f, nets %.2f; food a person a day %s; fish pull %.2f".format(
                    third.map { it.first.toDouble() }.average(), mean { it.skills[Skill.FISH] }, mean { it.skills[Skill.SMALL_GAME] },
                    mean { it.skills[Skill.HUNT] }, mean { it.kit[Tool.NET] },
                    FoodFrom.entries.joinToString(", ") { f -> "%s %.2f".format(f.label, mean { it.foodFrom[f] }) }, mean { it.fishPull })
            }
        }
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
                    100.0 * inRain.count { it.band!!.house >= Thresholds.HOUSE_STANDING } / inRain.size.coerceAtLeast(1), inRain.size) } +
            designs(bands, bs)
    }

    /**
     * Finished houses by how they were built (by the room they give), the bands that know each way, and for the bands at home
     * this week: how much more food keeping warm takes them ([Band.warmth]) and how many ran into heat debt, and how much the
     * ground's warmth through sunken walls adds on average (whether the designs differ where bands live).
     */
    private fun designs(bands: List<HexGroup>, bs: List<Band>): String {
        val room = DoubleArray(Skill.entries.size)
        for (s in sim.groups.built) if (s.kind == Built.HOUSES && s.done) room[s.design.coerceAtLeast(0)] += (s.built * s.size).toDouble()
        val all = room.sum().coerceAtLeast(1e-9)
        val groups = sim.groups; val weather = sim.weather
        val home = bands.mapNotNull { g -> g.band?.takeIf { it.atHome }?.let { b -> Triple(weather.temperature[g.place.i], b, b.warmth(groups.airAt(g.place, weather), b.fire, b.homeShelter(), groups.days)) } }
        return " | finished houses by design " + Skill.BUILDING_WAYS.joinToString(", ") { w -> "%s %.0f%%".format(w.name.lowercase(), 100.0 * room[w.ordinal] / all) } +
            " (%.0f places of room); known by ".format(room.sum()) + Skill.BUILDING_WAYS.joinToString(", ") { w ->
                "%s %.0f%%".format(w.name.lowercase(), 100.0 * bs.count { it.knowsSkill[w] } / bs.size.coerceAtLeast(1)) } +
            " | at home %d: keeping warm takes %.0f%% more food, in heat debt %.0f%%, the ground's warmth adds %.2f °C".format(home.size,
                100.0 * (home.map { it.third.extra.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0),
                100.0 * home.count { it.third.debt > 0f } / home.size.coerceAtLeast(1),
                home.map { (c, b) -> (b.homeShelter() * b.homeEarth * maxOf(0f, b.homeGroundC - c)).toDouble() }.average().takeIf { it.isFinite() } ?: 0.0) +
            worth(bands)
    }

    /**
     * What a whole house would be worth to each band where it is now, a grown person a year, averaged over the bands: the
     * lives it would save ([houseLivesAYear]) and the food ([houseFoodAYear], also as a share of the year's need).
     */
    private fun worth(bands: List<HexGroup>): String {
        val groups = sim.groups; val weather = sim.weather
        val lives = bands.mapNotNull { g -> g.band?.let { b -> groups.houseLivesAYear(b, g.place, weather).toDouble() } }
        val food = bands.mapNotNull { g -> g.band?.let { b -> groups.houseFoodAYear(g, b, g.place, weather).toDouble() } }
        val need = bands.firstOrNull()?.species?.dailyIntakeKg?.times(Date.DAYS_PER_YEAR)?.toDouble() ?: 1.0
        fun List<Double>.mean() = if (isEmpty()) 0.0 else average()
        val bs = bands.mapNotNull { it.band }
        val livedLives = bs.map { it.houseLives.toDouble() }; val livedFood = bs.map { it.houseFood.toDouble() }
        return " | a house would be worth a person a year, at usual temperatures: lives %.4f, food %.0f kg (%.1f%% of need); as the bands lived the year: lives %.4f, food %.0f kg (%.1f%% of need), bands where it saves a life per 100 person-years or more %.0f%%, or 1%% of need %.0f%%".format(
            lives.mean(), food.mean(), 100.0 * food.mean() / need, livedLives.mean(), livedFood.mean(), 100.0 * livedFood.mean() / need,
            100.0 * livedLives.count { it >= 0.01 } / bs.size.coerceAtLeast(1), 100.0 * livedFood.count { it >= 0.01 * need } / bs.size.coerceAtLeast(1))
    }

    /** What became of kept animals since the last census ([HerdFlow]): heads in and out by cause, and the shares herded and fed. */
    private fun herdFlows(): String {
        val f = sim.groups.herdFlows
        val kg = f[HerdFlow.KG].coerceAtLeast(1e-6f)
        return "; since last: born %.0f, died %.0f, ran off %.0f, culled for want of herders %.0f, eaten of the increase %.0f, milk %.0f t; herded %.2f, fed %.2f, in herds that pay %.2f".format(
            f[HerdFlow.BORN], f[HerdFlow.DIED], f[HerdFlow.RAN_OFF], f[HerdFlow.CULLED], f[HerdFlow.EATEN], f[HerdFlow.MILK_KG] / 1000f, f[HerdFlow.KG_TENDED] / kg, f[HerdFlow.KG_FED] / kg, f[HerdFlow.KG_PAYS] / kg)
            .also { f.fill(0f) }
    }

    /** What bands hold a grown person (what a camp move must carry or leave), kept herds, ornaments and shell, grave goods. */
    private fun goods(bands: List<HexGroup>, bs: List<Band>): String {
        val hw = sim.hw; val land = sim.land; val fauna = sim.fauna
        val adults = bands.sumOf { it.adults }.coerceAtLeast(1).toDouble()
        return " | held an adult: food %.1f kg, goods %.1f kg (".format(bands.sumOf { it.larder.toDouble() } / adults,
                bs.sumOf { it.goods.sum().toDouble() } / adults) +
            Material.entries.joinToString(", ") { m -> "%s %.1f".format(m.name.lowercase(), bs.sumOf { it.goods[m].toDouble() } / adults) } + ")" +
            " | kept herds: " + fauna.kinds.indices.filter { k -> bs.any { it.herd.size > k && it.herd[k] >= 1f } }.joinToString(", ") { k ->
                val keepers = bs.filter { it.herd.size > k && it.herd[k] >= 1f }
                "%s %.0f head in %d bands, %d of them mobile (tame %.2f)".format(fauna.kinds[k].name, keepers.sumOf { it.herd[k].toDouble() },
                    keepers.size, keepers.count { sim.groups.bandLifeway(it) == Tally.Lifeway.MOBILE }, keepers.map { it.herdTame[k].toDouble() }.average()) }.ifEmpty { "none" } +
            herdFlows() +
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
        fun yearly(cause: String) = deaths.thisYear(Tally.of(Catalogue.HUMAN.name, cause))
        val causes = deaths.causes
        val died = causes.keys.filter { it.startsWith(Tally.of(Catalogue.HUMAN)) && !it.text.endsWith(Tally.BORN) && !it.text.endsWith(":" + Tally.HUNTED) }.sumOf { yearly(Tally.whatOf(it)) }
        val childDied = (hunting.year[Tally.of(Catalogue.HUMAN, Tally.OfKind.CHILDREN_DIED)] ?: 0).toDouble()
        val children = childWeeks / weeks; val adults = adultWeeks / weeks
        val childQ = childDied / children.coerceAtLeast(1.0)
        val adultQ = ((died - childDied).coerceAtLeast(0.0)) / adults.coerceAtLeast(1.0)
        val meat = (hunting.year[Tally.of(Catalogue.HUMAN, Tally.OfKind.MEAT_EATEN)] ?: 0).toDouble()
        val plants = (hunting.year[Tally.of(Catalogue.HUMAN, Tally.OfKind.PLANTS_EATEN)] ?: 0).toDouble()
        val violent = yearly(DeathCause.RAIDS.label) +
            causes.keys.filter { it.text.startsWith(Tally.of(Catalogue.HUMAN).text + ByBeast.How.KILLED_BY.words) }.sumOf { yearly(Tally.whatOf(it)) }
        val bandsSeen = walked.size.coerceAtLeast(1)
        run {
            val all = sim.groups.stats.childDeaths.mapValues { it.value.sumThenReset() }.filter { it.value > 0.0 }
            val total = all.values.sum().coerceAtLeast(1e-9)
            println("     children's deaths by cause this year (expected from the odds, share): " + all.entries.sortedByDescending { it.value }
                .joinToString(", ") { "%s %.0f%%".format(it.key, 100 * it.value / total) })
        }
        val hex = sim.hex
        println("     people against the record: band size %.0f [15-50, most 20-35] | people per km² %.3f [foragers 0.01-0.4; Mediterranean 0.05-0.2] | camps moved a year %.1f [5-60, median about 15] | ground walked a year %.0f km² [200-2500] | plant share of food %.0f%% [Mediterranean 40-60%%] | births a woman over her bearing years %.1f [foragers 3.5-7.9, mean 5.6; Hadza 6.2, !Kung 4.3-4.7] | children reaching 15 %.0f%% [about 55-65%%] | years left at 15 %.0f [about 35-40] | violent deaths %.0f%% [2-15%%]".format(
            bandHeads / bandWeeks, bandHeads / weeks / sim.land.areaKm2.sum(), moves.toDouble() / bandsSeen,
            walked.values.map { it.size }.average() * hex.areaKm2, 100 * plants / (meat + plants).coerceAtLeast(1.0),
            ADULT_YEARS * yearly(Tally.BORN) / (motherWeeks / weeks).coerceAtLeast(1.0), 100 * Math.pow((1 - childQ).coerceIn(0.0, 1.0), 15.0),
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
