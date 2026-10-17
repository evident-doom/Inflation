package inflation.ui

import inflation.ecology.HexFauna
import inflation.ecology.get
import inflation.ecology.HexSim
import inflation.ecology.PlaceRecord
import inflation.ecology.Lifeway
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min

/**
 * What the viewer tints the hexes by, worked out hex by hex once a week (on the simulation's
 * thread), and kept month by month over the last year for the seasonal views: how much each hex
 * swings over the year, in which month it peaks, and how this month stands against the same month
 * a year before. Each value is a hex's area-weighted mean over its height bands; NaN where a hex
 * has no land.
 */
internal class ViewLayers(private val sim: HexSim) {
    /** A measure to tint by: its name in the list, its unit, the colour it is shaded in, and whether it can go below 0. */
    enum class Measure(val label: String, val unit: String, val r: Float, val g: Float, val b: Float, val signed: Boolean = false) {
        NONE("Ground only", "", 0f, 0f, 0f),
        TEMPERATURE("Temperature", "°C", 1f, 0.4f, 0.1f, signed = true), SNOW("Snow lying", "mm", 1f, 1f, 1f),
        RAIN("Rain this week", "mm", 0.1f, 0.3f, 1f),
        GREEN("Green forage", "share green", 0.1f, 0.9f, 0.1f), FORAGE("Grass and browse", "kg/m²", 0.1f, 0.7f, 0.1f),
        HERBS("Grass and herbs only", "kg/m²", 0.4f, 0.8f, 0.1f),
        SOIL("Soil water", "mm", 0.2f, 0.3f, 1f), WATER("Drinking water", "share within reach", 0.1f, 0.4f, 1f),
        WATER_KM("Walk to water", "km", 0.8f, 0.5f, 0.1f),
        SMALL_GAME("Rodents and reptiles", "kg/km²", 0.9f, 0.5f, 0.1f), FOXES("Foxes", "kg/km²", 0.9f, 0.35f, 0.05f),
        OWLS("Owls and small hawks", "kg/km²", 0.5f, 0.4f, 0.3f),
        EAGLES("Eagles", "share of eagle ranges held", 0.3f, 0.2f, 0.1f), INSECTS("Insects", "kg/km²", 0.6f, 0.8f, 0.1f),
        BITING("Biting insects", "kg/km²", 0.8f, 0.1f, 0.1f),
        POLLINATORS("Pollinators", "kg/km²", 1f, 0.85f, 0.1f), ORGANIC("Soil organic matter", "kg/m²", 0.35f, 0.2f, 0.05f),
        CARRION("Carrion", "kg/km²", 0.4f, 0.1f, 0.4f),
        RESIDENTS("Resident herds (all)", "head/km²", 1f, 0.1f, 0.6f),
        DEATHS("Where animals die", "kg/km² a week", 0.6f, 0f, 0f), PEOPLE_DEATHS("Where people die", "deaths per 1000 km² a week", 0.6f, 0f, 0.3f),
        KILLS("Kills by packs and people", "kg/km² a week", 0.85f, 0.05f, 0.05f),
        HUNTERS_PER_PREY("Hunters per prey", "kg hunters per 100 kg big game (blank under 1 kg/km² of game)", 0.9f, 0.2f, 0.1f),
        HUNGRY_BY_PLENTY("Hungry next to plenty", "hunger x forage left within 2 hexes", 1f, 0.2f, 0.8f),
        CARRION_WEEKS("Weeks to clear: carrion", "weeks", 0.4f, 0.1f, 0.4f), LITTER_WEEKS("Weeks to clear: litter", "weeks", 0.45f, 0.3f, 0.1f),
        BONES_WEEKS("Weeks to clear: bones", "weeks", 0.8f, 0.8f, 0.7f), ORGANIC_WEEKS("Weeks to clear: soil organic matter", "weeks", 0.35f, 0.2f, 0.05f);
        val byCause get() = this == DEATHS || this == PEOPLE_DEATHS
        val weeks get() = this == CARRION_WEEKS || this == LITTER_WEEKS || this == BONES_WEEKS || this == ORGANIC_WEEKS
    }

    /** How the measure is shown. */
    enum class Mode(val label: String) { WEEK("this week"), SCALED("this week, scaled to the map"), SWING("swing over the year"),
        PEAK("month of its peak"), YEAR_AGO("against a year ago") }

    private val hex = sim.hex; private val hw = sim.hw; private val land = sim.land; private val fauna = sim.fauna; private val groups = sim.groups
    private val n = fauna.n
    private val nh = hex.size
    val record = PlaceRecord(n, fauna.kinds.size).also { fauna.record = it }

    /** The keys a measure is kept under: one, or one per cause and one for all causes together. */
    fun key(m: Measure, cause: Int) = if (m.byCause) "${m.name}/$cause" else m.name
    private val keys = Measure.entries.filter { it != Measure.NONE }
        .flatMap { m -> if (m.byCause) (0..PlaceRecord.Cause.entries.size).map { key(m, it) } else listOf(m.name) }

    /** This week's value of each measure, per hex. */
    val now = HashMap<String, FloatArray>()
    /** Each month's mean over the last year (index: month), per hex; null until that month has been seen. */
    private val months = HashMap<String, Array<FloatArray?>>()
    /** The same month a year before the last one kept (index: month), per hex; null until two of it have been seen. */
    private val yearBefore = HashMap<String, Array<FloatArray?>>()
    private val sums = HashMap<String, DoubleArray>()
    /** Weeks this month each hex had a value of each measure (a measure can be blank some weeks, see [MIN_GAME_KG_KM2]). */
    private val counts = HashMap<String, IntArray>()
    private var weeksInMonth = 0
    private var month = -1
    /** The month last completed, -1 before any. */
    var lastMonth = -1; private set

    // Kept from week to week: deaths, kills and dead matter gone, as running means over about a month.
    private val deaths = Array(2) { Array(PlaceRecord.Cause.entries.size) { FloatArray(nh) } }
    private val killsKg = FloatArray(nh)
    private val gone = Array(4) { FloatArray(nh) }
    private var fearBefore: FloatArray? = null
    private var weeksSeen = 0

    /** Forgets the record (after a load, which moves the date). */
    fun reset() {
        months.clear(); yearBefore.clear(); sums.clear(); counts.clear(); weeksInMonth = 0; month = -1; lastMonth = -1
        for (a in deaths) for (b in a) b.fill(0f)
        killsKg.fill(0f); for (a in gone) a.fill(0f); fearBefore = null; weeksSeen = 0
        record.clear()
    }

    /** After each week: works out every measure and adds it to the month. */
    fun week() {
        val area = DoubleArray(nh)
        for (p in 0 until n) area[hw.placeHex[p]] += land.areaKm2[p].toDouble()
        fun hexMean(f: (Int) -> Float): FloatArray {
            val s = DoubleArray(nh)
            for (p in 0 until n) s[hw.placeHex[p]] += f(p) * land.areaKm2[p].toDouble()
            return FloatArray(nh) { if (area[it] > 0) (s[it] / area[it]).toFloat() else Float.NaN }
        }
        fun hexSum(f: (Int) -> Float): FloatArray {
            val s = DoubleArray(nh)
            for (p in 0 until n) s[hw.placeHex[p]] += f(p).toDouble()
            return FloatArray(nh) { if (area[it] > 0) s[it].toFloat() else Float.NaN }
        }
        val w = sim.weather; val sl = fauna.small
        now[Measure.TEMPERATURE.name] = hexMean { w.temperature[it] }
        now[Measure.SNOW.name] = hexMean { w.snowpack[it] }
        now[Measure.RAIN.name] = hexMean { w.precipitation[it] }
        now[Measure.GREEN.name] = hexMean { land.plants.green[it] }
        now[Measure.FORAGE.name] = hexMean { land.plants.herb[it] + land.plants.shrub[it] }
        now[Measure.HERBS.name] = hexMean { land.plants.herb[it] }
        now[Measure.SOIL.name] = hexMean { land.hydro.soil[it] }
        now[Measure.WATER.name] = hexMean { land.drink.water[it] }
        now[Measure.WATER_KM.name] = hexMean { land.drink.waterKm[it] }
        now[Measure.SMALL_GAME.name] = hexMean { sl.rodents[it] + sl.herps[it] }
        now[Measure.FOXES.name] = hexMean { sl.foxes[it] }
        now[Measure.OWLS.name] = hexMean { sl.owls[it] }
        now[Measure.EAGLES.name] = hexMean { sl.eagles[it] }
        now[Measure.INSECTS.name] = hexMean { sl.insects[it] }
        now[Measure.BITING.name] = hexMean { sl.biting[it] }
        now[Measure.POLLINATORS.name] = hexMean { sl.pollinators[it] }
        now[Measure.ORGANIC.name] = hexMean { sl.organicMatter[it] }
        now[Measure.CARRION.name] = hexMean { sl.carrion[it] * 1e6f }
        now[Measure.RESIDENTS.name] = hexMean { p -> var v = 0f; for (k in fauna.kinds.indices) v += fauna.adults[k][p] + fauna.young[k][p]; v }

        // Deaths: this week's, by cause, as a running mean over about a month (a single week is mostly noise).
        val keep = if (weeksSeen == 0) 0f else KEEP
        for ((scope, src) in listOf(record.animalKg, record.people).withIndex()) {
            for (c in PlaceRecord.Cause.entries.indices) {
                val week = hexSum { src[c][it] }
                val d = deaths[scope][c]
                for (h in 0 until nh) d[h] = if (area[h] > 0) keep * d[h] +
                    (1f - keep) * (week[h] / area[h].toFloat() * (if (scope == 1) 1000f else 1f)) else 0f
            }
        }
        for ((scope, m) in listOf(Measure.DEATHS, Measure.PEOPLE_DEATHS).withIndex()) {
            val all = FloatArray(nh) { if (area[it] > 0) 0f else Float.NaN }
            for (c in PlaceRecord.Cause.entries.indices) {
                now[key(m, c)] = FloatArray(nh) { if (area[it] > 0) deaths[scope][c][it] else Float.NaN }
                for (h in 0 until nh) all[h] += deaths[scope][c][h]
            }
            now[key(m, PlaceRecord.Cause.entries.size)] = all
        }

        // Kills by packs and people: what was added to the places' fear this week (it fades by [HexFauna.FEAR_KEEP] a week).
        val before = fearBefore
        val killed = hexSum { p -> if (before == null) 0f else max(0f, fauna.fear[p] - before[p] * HexFauna.FEAR_KEEP) * land.areaKm2[p] }
        fearBefore = fauna.fear.copyOf()
        for (h in 0 until nh) if (area[h] > 0) killsKg[h] = keep * killsKg[h] + (1f - keep) * (killed[h] / area[h].toFloat())
        now[Measure.KILLS.name] = FloatArray(nh) { if (area[it] > 0) killsKg[it] else Float.NaN }

        // Hunters per prey: kg of hunting animals (packs and hunting residents; not people) against kg of game, over a hex and its ring.
        // Where there is next to no big game the ratio is two near-zero numbers (jackals living on rodents), so it is left blank.
        val hunterKg = DoubleArray(nh); val preyKg = DoubleArray(nh)
        for ((k, s) in fauna.kinds.withIndex()) for (p in 0 until n) {
            val kg = (fauna.adults[k][p] + 0.3f * fauna.young[k][p]) * land.areaKm2[p] * s.massKg
            if (s.carnivore) hunterKg[hw.placeHex[p]] += kg.toDouble() else preyKg[hw.placeHex[p]] += kg.toDouble()
        }
        for (g in groups.groups) {
            if (!g.alive || g.species.human) continue
            val kg = (g.adults + 0.3 * g.young) * g.species.massKg
            if (g.species.carnivore) hunterKg[hw.placeHex[g.place]] += kg else preyKg[hw.placeHex[g.place]] += kg
        }
        now[Measure.HUNTERS_PER_PREY.name] = FloatArray(nh) { h ->
            if (area[h] <= 0) return@FloatArray Float.NaN
            var hu = hunterKg[h]; var pr = preyKg[h]; var km2 = area[h]
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0) { hu += hunterKg[q]; pr += preyKg[q]; km2 += area[q] } }
            if (pr < MIN_GAME_KG_KM2 * km2) Float.NaN else min(100.0, 100.0 * hu / pr).toFloat()
        }

        // Hungry next to plenty: how far short of their wants the plant-eaters are here, times the most grass and browse left uneaten within two hexes.
        val hungerSum = DoubleArray(nh); val heads = DoubleArray(nh)
        for ((k, s) in fauna.kinds.withIndex()) {
            if (s.carnivore) continue
            for (p in 0 until n) {
                val hd = (fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]
                if (hd > 0f) { hungerSum[hw.placeHex[p]] += hd * (1.0 - fauna.fed[k][p]); heads[hw.placeHex[p]] += hd.toDouble() }
            }
        }
        for (g in groups.groups) if (g.alive && g.species.lifeway == Lifeway.HERD) {
            hungerSum[hw.placeHex[g.place]] += g.size * (1.0 - g.fed)
            heads[hw.placeHex[g.place]] += g.size.toDouble()
        }
        val left = hexMean { fauna.grazeLeft[it] + fauna.browseLeft[it] }
        hunger = FloatArray(nh) { if (heads[it] > 0) (hungerSum[it] / heads[it]).toFloat() else 0f }
        plenty = FloatArray(nh) { h ->
            var best = 0f
            for (q in within2(h)) { val v = left[q]; if (!v.isNaN() && v > best) best = v }
            best
        }
        now[Measure.HUNGRY_BY_PLENTY.name] = FloatArray(nh) { if (area[it] > 0) hunger[it] * plenty[it] else Float.NaN }

        // Weeks to clear: what lies on the ground over what went in a week (a running mean over about a month).
        val stocks = listOf(Measure.CARRION_WEEKS to sl.carrion, Measure.LITTER_WEEKS to sl.litter,
            Measure.BONES_WEEKS to sl.bones, Measure.ORGANIC_WEEKS to sl.organicMatter)
        val went = listOf(record.carrionGone, record.litterGone, record.bonesGone, record.organicGone)
        for ((i, pair) in stocks.withIndex()) {
            val (m, stock) = pair
            val wk = hexMean { went[i][it] }
            val st = hexMean { stock[it] }
            val g = gone[i]
            for (h in 0 until nh) if (area[h] > 0) g[h] = keep * g[h] + (1f - keep) * wk[h]
            now[m.name] = FloatArray(nh) { h ->
                if (area[h] <= 0) Float.NaN else if (st[h] <= 0f) 0f else if (g[h] <= 0f) MAX_WEEKS else min(MAX_WEEKS, st[h] / g[h])
            }
        }
        record.clear()
        weeksSeen++

        // Into the month's sums; at the month's end, its means.
        val m = sim.weather.date.month
        if (month >= 0 && m != month) closeMonth()
        month = m
        for (k in keys) {
            val v = now[k] ?: continue
            val s = sums.getOrPut(k) { DoubleArray(nh) }; val c = counts.getOrPut(k) { IntArray(nh) }
            for (h in 0 until nh) if (!v[h].isNaN()) { s[h] += v[h].toDouble(); c[h]++ }
        }
        weeksInMonth++
    }

    /** The plant-eaters' hunger (1 - fed) and the most forage left within two hexes, kg/m², from the last week (for the inspector). */
    var hunger = FloatArray(0); private set
    var plenty = FloatArray(0); private set

    private val ring2 = HashMap<Int, IntArray>()
    private fun within2(h: Int): IntArray = ring2.getOrPut(h) {
        val out = linkedSetOf(h)
        for (d in 0 until 6) {
            val q = hex.neighbour(h, d)
            if (q >= 0) { out += q; for (e in 0 until 6) { val r = hex.neighbour(q, e); if (r >= 0) out += r } }
        }
        out.toIntArray()
    }

    private fun closeMonth() {
        if (weeksInMonth == 0) return
        for ((k, s) in sums) {
            val c = counts.getValue(k)
            val mean = FloatArray(nh) { if (c[it] > 0) (s[it] / c[it]).toFloat() else Float.NaN }
            val slots = months.getOrPut(k) { arrayOfNulls(12) }
            slots[month]?.let { yearBefore.getOrPut(k) { arrayOfNulls(12) }[month] = it }
            slots[month] = mean
            s.fill(0.0); c.fill(0)
        }
        // A hex with no land stays NaN.
        val land = now[Measure.TEMPERATURE.name]
        if (land != null) for (slots in months.values) slots[month]?.let { a -> for (h in 0 until nh) if (land[h].isNaN()) a[h] = Float.NaN }
        lastMonth = month
        weeksInMonth = 0
    }

    /** How many of the last twelve months have been kept for [key]. */
    fun monthsKept(key: String) = months[key]?.count { it != null } ?: 0

    /** [key]'s monthly means at hex [h], by month (NaN where not kept). */
    fun byMonth(key: String, h: Int): FloatArray = FloatArray(Date.MONTHS_PER_YEAR) { months[key]?.get(it)?.get(h) ?: Float.NaN }

    /**
     * The swing over the year at each hex: (highest month - lowest) / mean of the months, or for a measure
     * that can go below 0, highest - lowest; NaN with fewer than two months.
     */
    fun swing(key: String, signed: Boolean): FloatArray? {
        val slots = months[key]?.filterNotNull() ?: return null
        if (slots.size < 2) return null
        return FloatArray(nh) { h ->
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var sum = 0.0; var c = 0
            for (a in slots) { val v = a[h]; if (v.isNaN()) continue; lo = min(lo, v); hi = max(hi, v); sum += v; c++ }
            if (c < 2) Float.NaN else if (signed) hi - lo else { val mean = sum / c; if (mean <= 0.0) 0f else ((hi - lo) / mean).toFloat() }
        }
    }

    /** The month (0..11) of the highest monthly mean at each hex, -1 where none. */
    fun peak(key: String): IntArray? {
        val slots = months[key] ?: return null
        if (slots.count { it != null } < 2) return null
        return IntArray(nh) { h ->
            var best = -1; var bv = -Float.MAX_VALUE
            for (m in 0 until Date.MONTHS_PER_YEAR) { val v = slots[m]?.get(h) ?: continue; if (!v.isNaN() && v > bv) { bv = v; best = m } }
            best
        }
    }

    /**
     * The last month completed against the same month a year before: their ratio, or for a measure that can
     * go below 0 their difference; null until there is a year before.
     */
    fun yearAgo(key: String, signed: Boolean): FloatArray? {
        if (lastMonth < 0) return null
        val a = months[key]?.get(lastMonth) ?: return null
        val b = yearBefore[key]?.get(lastMonth) ?: return null
        return FloatArray(nh) { h ->
            if (a[h].isNaN() || b[h].isNaN()) Float.NaN
            else if (signed) a[h] - b[h]
            else if (a[h] <= 0f && b[h] <= 0f) 1f
            else (a[h] + 1e-9f) / (b[h] + 1e-9f)
        }
    }

    companion object {
        /** Share of the running mean kept each week (about a month's memory). */
        const val KEEP = 0.75f
        /** Weeks to clear are shown up to ten years. */
        const val MAX_WEEKS = 520f
        /** Hunters per prey is shown only where the hex and its ring hold at least this much big game, kg/km². */
        const val MIN_GAME_KG_KM2 = 1.0
    }
}
