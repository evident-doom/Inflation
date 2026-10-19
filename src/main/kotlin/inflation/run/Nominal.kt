package inflation.run

import inflation.ecology.Catalogue
import inflation.ecology.HexHistory
import inflation.ecology.HexHistory.Key
import inflation.ecology.HexSim
import inflation.world.WorldConfig
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a run stayed within nominal limits: wide bounds on every series the history keeps (what the history graphs
 * show) and on the state the run ends in (the tables the viewer's map layers are drawn from), so that a run gone wrong,
 * a number that is not one, matter below zero, a kind gone, a stock running away, is caught after any test, A/B or
 * training check rather than found later by eye. Reported only: a finding does not stop or fail the run.
 *
 * The limits are lax on purpose (Olli, 2026-10-19): a finding says the run left what the model has done in settled
 * runs so far, not that it is wrong in detail. They were set against the storm and silt worlds' histories (3 and 20
 * years) with room to spare, and are to be tightened here as the model settles. Years are whole years from the
 * history's first week; a year cut short at the end is left out.
 */
object Nominal {
    // ---- the limits

    /** How well fed a plant-eating kind may be: at most fully (with float slack), and over a whole year at least this. */
    const val FED_MAX = 1.001
    const val FED_YEAR_MIN = 0.5
    /** The land's mean temperature, °C: in any week, and over a year. */
    const val TEMPERATURE_WEEK_MIN = -45.0; const val TEMPERATURE_WEEK_MAX = 50.0
    const val TEMPERATURE_YEAR_MIN = -15.0; const val TEMPERATURE_YEAR_MAX = 35.0
    /** Rain on the land, mm: the mean in any week, and its sum over a year. */
    const val RAIN_WEEK_MAX = 300.0
    const val RAIN_YEAR_MIN = 50.0; const val RAIN_YEAR_MAX = 4000.0
    /** The land's mean snow lying and soil water, mm. */
    const val SNOW_MAX_MM = 10_000.0
    const val SOIL_WATER_MAX_MM = 3000.0
    /** Areas (the land's, each kind of country's) may change by this share over a run: they should not change at all. */
    const val AREA_DRIFT = 1e-4
    /** Plants over the whole land and in any kind of country, kg/m². */
    const val PLANTS_MIN_KG_M2 = 0.02; const val PLANTS_MAX_KG_M2 = 60.0
    const val BIOME_PLANTS_MAX_KG_M2 = 100.0
    /** All big animals (residents, herds, packs and bands) over the land, t/km² (wild ungulates reach some 20 t/km² at their richest). */
    const val ANIMALS_MAX_T_KM2 = 30.0
    /** People over the land, per km² (foragers live at a hundredth to a few tenths). */
    const val PEOPLE_MAX_KM2 = 2.0
    /** The migratory herds' share above 1000 m, mean height (m) and distance from half a year before (km). */
    const val HEIGHT_MIN_M = -450.0; const val HEIGHT_MAX_M = 9000.0
    const val KM_FROM_HALF_A_YEAR_MAX = 3000.0
    /** A kind's deaths, and young born, in a year, per head it numbered on average. */
    const val DEATHS_PER_HEAD = 2.0
    const val BIRTHS_PER_HEAD = 2.0

    /**
     * How a state may change from year to year ([yearly], times either way) and from the first whole year to the last
     * ([run]), once it is at least [floor] in either year (a few animals or tonnes rise and fall too much by chance to
     * judge by); and whether it may come to nothing, a kind or pool there in the first year and gone at the end.
     */
    class Drift(val yearly: Double, val run: Double, val floor: Double)
    /** A kind's numbers (Olli: mild cycles, no extinction). */
    val ANIMALS = Drift(yearly = 3.0, run = 10.0, floor = 20.0)
    /** The big slow stocks of plants and soil, thousand tonnes. */
    val SLOW_STOCKS = Drift(yearly = 1.5, run = 4.0, floor = 1.0)
    val SLOW = setOf("plants", "herbs", "shrubs", "trees", "litter", "soil organic matter", "soil animals")
    /** Every other pool of matter (thousand tonnes), of small life (tonnes or heads) and of water life. */
    val POOLS = Drift(yearly = 5.0, run = 20.0, floor = 0.01)
    val SMALL_AND_WATER_LIFE = Drift(yearly = 5.0, run = 20.0, floor = 1.0)
    /** Snow lying (mm over the land): it must not pile up from year to year. */
    val SNOW = Drift(yearly = 2.0, run = 4.0, floor = 1.0)

    /** Residents of all kinds in any one place, t/km². */
    const val RESIDENTS_MAX_T_KM2 = 100.0
    /** Any place's temperature, °C, and its snow lying, mm of water. */
    const val PLACE_TEMPERATURE_MIN = -70.0; const val PLACE_TEMPERATURE_MAX = 60.0
    const val PLACE_SNOW_MAX_MM = 20_000.0
    /** What a table of a stock may hold below zero, as a share of its largest value (float rounding). */
    const val STOCK_BELOW_ZERO = 1e-6
    /** What the books may leave unexplained over the run (audit=true), as a share of the stock now. */
    const val BOOKS_LEFT_OVER = 1e-3

    // ---- the checks

    /** What was out of limits: the series or table, and how. */
    class Finding(val what: String, val how: String) {
        override fun toString() = "$what: $how"
    }

    /** What a check found, and how many limits it tried. */
    class Result(val findings: List<Finding>, val tried: Int, val what: String) {
        val ok get() = findings.isEmpty()
        /** A line, and one line a finding (the first [most] of them). */
        fun report(most: Int = 40): String = buildString {
            append("within nominal limits ($what): ")
            if (ok) append("yes, all $tried limits held") else {
                append("NO, ${findings.size} of $tried limits out")
                for (f in findings.take(most)) append("\n  ").append(f)
                if (findings.size > most) append("\n  ... and ${findings.size - most} more")
            }
        }
        /** One line: yes, or how many were out and the first few. */
        fun brief(few: Int = 3) = if (ok) "yes" else "NO, ${findings.size} out: " + findings.take(few).joinToString("; ") + if (findings.size > few) "; ..." else ""
    }

    private class Tally { val findings = ArrayList<Finding>(); var tried = 0
        fun check(ok: Boolean, what: String, how: () -> String) { tried++; if (!ok) findings += Finding(what, how()) }
    }

    /** Every series of [h] against its limits. */
    fun history(h: HexHistory): Result {
        val t = Tally()
        val w = WorldConfig().stepsPerYear
        val years = h.weeks / w
        fun week(k: String, i: Int) = h.series.getValue(k)[i].toDouble()
        fun yearly(k: String, y: Int): Double {
            val s = h.series[k] ?: return Double.NaN
            var sum = 0.0; var c = 0
            for (i in y * w until (y + 1) * w) { val v = s[i]; if (!v.isNaN()) { sum += v; c++ } }
            return if (c == 0) Double.NaN else if (Key.isFlow(k)) sum else sum / c
        }
        fun at(i: Int) = "year ${i / w + 1}, week ${i % w + 1}"
        val area = h.series[Key.LAND_AREA]?.let { s -> (0 until s.size).map { s[it].toDouble() }.lastOrNull { !it.isNaN() } } ?: Double.NaN

        for (k in h.series.keys) {
            val s = h.series.getValue(k)
            // Every number a number, and none below zero but those that may be.
            var first = -1; var bad = 0; var badAt = -1; var neg = 0; var negAt = -1; var gaps = 0
            for (i in 0 until s.size) {
                val v = s[i]
                if (v.isNaN()) { if (first >= 0) gaps++; continue }
                if (first < 0) first = i
                if (v.isInfinite()) { bad++; if (badAt < 0) badAt = i }
                else if (v < 0f && !signed(k)) { neg++; if (negAt < 0) negAt = i }
            }
            t.check(bad == 0, k) { "not a number in $bad weeks, first in ${at(badAt)}" }
            t.check(neg == 0, k) { "below zero in $neg weeks, first in ${at(negAt)} (${s[negAt]})" }
            // (Series of states always recorded: a gap is a week the record lost, or a number that was not one.)
            if (always(k)) t.check(gaps == 0, k) { "missing or not a number in $gaps weeks after its first" }
            if (first < 0) continue
            fun weekly(lo: Double, hi: Double, unit: String) {
                var out = 0; var outAt = -1
                for (i in first until s.size) { val v = s[i]; if (!v.isNaN() && (v < lo || v > hi)) { out++; if (outAt < 0) outAt = i } }
                t.check(out == 0, k) { "outside %s..%s %s in %d weeks, first in %s (%s)".format(fmt(lo), fmt(hi), unit, out, at(outAt), fmt(week(k, outAt))) }
            }
            fun eachYear(lo: Double, hi: Double, unit: String, scale: Double = 1.0) {
                for (y in 0 until years) { val v = scale * yearly(k, y); if (!v.isNaN()) t.check(v in lo..hi, k) { "%s %s in year %d, outside %s..%s".format(fmt(v), unit, y + 1, fmt(lo), fmt(hi)) } }
            }
            when {
                Key.isFed(k) -> { weekly(0.0, FED_MAX, ""); eachYear(FED_YEAR_MIN, FED_MAX, "fed over the year") }
                k == Key.TEMPERATURE -> { weekly(TEMPERATURE_WEEK_MIN, TEMPERATURE_WEEK_MAX, "°C"); eachYear(TEMPERATURE_YEAR_MIN, TEMPERATURE_YEAR_MAX, "°C") }
                k == Key.RAIN -> { weekly(0.0, RAIN_WEEK_MAX, "mm a week"); eachYear(RAIN_YEAR_MIN, RAIN_YEAR_MAX, "mm a year") }
                k == Key.SNOW -> { weekly(0.0, SNOW_MAX_MM, "mm"); drift(t, k, years, ::yearly, SNOW, gone = false) }
                k == Key.SOIL_WATER -> weekly(0.0, SOIL_WATER_MAX_MM, "mm")
                k == Key.LAND_AREA || Key.biomeWhat(k) == Key.AREA -> {
                    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                    for (i in first until s.size) { val v = s[i].toDouble(); if (!v.isNaN()) { lo = min(lo, v); hi = max(hi, v) } }
                    t.check(hi - lo <= AREA_DRIFT * hi, k) { "changed from %s to %s km² over the run".format(fmt(lo), fmt(hi)) }
                }
                Key.biomeWhat(k) == Key.PLANTS -> weekly(0.0, BIOME_PLANTS_MAX_KG_M2, "kg/m²")
                // (The herds' own series: dropped with the herds, if they go.)
                Key.migrationOf(k) == HexHistory.Migration.SHARE_ABOVE_1000_M -> weekly(0.0, 1.0, "")
                Key.migrationOf(k) == HexHistory.Migration.MEAN_HEIGHT_M -> weekly(HEIGHT_MIN_M, HEIGHT_MAX_M, "m")
                Key.migrationOf(k) == HexHistory.Migration.KM_FROM_HALF_A_YEAR -> weekly(0.0, KM_FROM_HALF_A_YEAR_MAX, "km")
                Key.animalKind(k) != null -> {
                    drift(t, k, years, ::yearly, ANIMALS, gone = true)
                    val kind = Key.animalKind(k)!!
                    // Present in the first week and none in the last: gone (not judged by the year's mean, which a kind dying out in its last weeks keeps above 0).
                    val last = (s.size - 1 downTo first).firstOrNull { !s[it].isNaN() }
                    if (last != null && last > first) t.check(!(s[first] > 0f && s[last] <= 0f), k) { "gone by the end (%s at the start)".format(fmt(s[first].toDouble())) }
                    if (kind == Catalogue.HUMAN.name && area > 0) eachYear(0.0, PEOPLE_MAX_KM2, "people per km²", 1 / area)
                    for (y in 0 until years) {
                        val heads = yearly(k, y)
                        if (!(heads >= ANIMALS.floor)) continue
                        val died = h.series.keys.filter { Key.deathsKind(it) == kind }.sumOf { yearly(it, y).let { v -> if (v.isNaN()) 0.0 else v } }
                        t.check(died <= DEATHS_PER_HEAD * heads, k) { "%s deaths in year %d of %s heads on average, over %s a head".format(fmt(died), y + 1, fmt(heads), fmt(DEATHS_PER_HEAD)) }
                        val born = yearly(Key.births(kind), y)
                        if (!born.isNaN()) t.check(born <= BIRTHS_PER_HEAD * heads, k) { "%s born in year %d of %s heads on average, over %s a head".format(fmt(born), y + 1, fmt(heads), fmt(BIRTHS_PER_HEAD)) }
                    }
                }
                Key.isBiomass(k) -> {
                    val name = k.substringAfter('/')
                    // (A kind's residents and groups are judged by its head count: one living the other way for a while is no fault.)
                    if (name.startsWith("resident ") || name.startsWith("group ")) continue
                    // (Fast pools may empty for a year, as the locusts between plagues; the slow stocks may not.)
                    drift(t, k, years, ::yearly, if (name in SLOW) SLOW_STOCKS else POOLS, gone = name in SLOW)
                    if (name == "plants" && area > 0) eachYear(PLANTS_MIN_KG_M2, PLANTS_MAX_KG_M2, "kg/m² of plants over the land", 1 / area)
                }
                Key.isSmallLife(k) || Key.isWaterLife(k) -> drift(t, k, years, ::yearly, SMALL_AND_WATER_LIFE, gone = true)
            }
        }
        // All big animals over the land, from the books' pools (thousand tonnes over km² is kt/km², times 1000 for t/km²).
        if (area > 0) {
            val pools = listOf(Key.biomass("resident animals"), Key.biomass("herds, packs and bands")).filter { it in h.series }
            if (pools.isNotEmpty()) for (y in 0 until years) {
                val tKm2 = pools.sumOf { yearly(it, y).let { v -> if (v.isNaN()) 0.0 else v } } * 1000 / area
                t.check(tKm2 <= ANIMALS_MAX_T_KM2, "big animals") { "%s t/km² over the land in year %d, over %s".format(fmt(tKm2), y + 1, fmt(ANIMALS_MAX_T_KM2)) }
            }
        }
        return Result(t.findings, t.tried, "series, ${h.series.size} over ${h.weeks} weeks")
    }

    private fun drift(t: Tally, k: String, years: Int, yearly: (String, Int) -> Double, d: Drift, gone: Boolean) {
        if (years < 1) return
        val ys = (0 until years).map { yearly(k, it) }
        for (y in 1 until years) {
            val a = ys[y - 1]; val b = ys[y]
            if (a.isNaN() || b.isNaN() || max(a, b) < d.floor) continue
            if (gone && a > 0 && b <= 0) { t.check(false, k) { "gone in year ${y + 1}" }; return }
            if (a <= 0 || b <= 0) continue
            t.check(b / a in 1 / d.yearly..d.yearly, k) { "%s to %s from year %d to %d, beyond %s times either way".format(fmt(a), fmt(b), y, y + 1, fmt(d.yearly)) }
        }
        val a = ys.first(); val b = ys.last()
        if (years > 2 && a > 0 && b > 0 && max(a, b) >= d.floor)
            t.check(b / a in 1 / d.run..d.run, k) { "%s in year 1 to %s in year %d, beyond %s times either way".format(fmt(a), fmt(b), years, fmt(d.run)) }
    }

    /** Series that may go below zero. */
    private fun signed(k: String) = k == Key.TEMPERATURE || k == Key.fishFlow("drift (net)")
    /** Series recorded every week from their first. */
    private fun always(k: String) = Key.animalKind(k) != null || Key.isBiomass(k) || Key.isWeather(k) || Key.isSmallLife(k) || Key.isWaterLife(k) || k == Key.LAND_AREA

    /** The state [sim] stands in now: every table a number, every stock at or above zero, shares within 0..1, groups sound, and the books (if kept). */
    fun state(sim: HexSim): Result {
        val t = Tally()
        val land = sim.land; val fauna = sim.fauna; val n = land.n
        // Every table of the weather, the land and the animals: numbers, all of them.
        val parts = listOf(sim.weather, land, land.hydro, land.drink, land.plants, land.fire, fauna, fauna.food, fauna.small, fauna.water)
        for (part in parts) for ((name, table) in tables(part)) {
            var bad = 0; var badAt = -1
            when (table) {
                is FloatArray -> for (i in table.indices) if (!table[i].isFinite()) { bad++; if (badAt < 0) badAt = i }
                is DoubleArray -> for (i in table.indices) if (!table[i].isFinite()) { bad++; if (badAt < 0) badAt = i }
            }
            t.check(bad == 0, name) { "$bad not numbers, first at index $badAt" }
        }
        // Water and matter: no stock below zero, in sum or in any place.
        for (s in sim.stocks().list) {
            val sum = s.sum()
            t.check(sum.isFinite() && sum >= 0.0, "stock ${s.name}") { "sums to $sum" }
            for ((i, a) in s.arrays.withIndex()) {
                val (lo, hi) = when (a) { is FloatArray -> a.minOrNull()?.toDouble() to a.maxOrNull()?.toDouble(); is DoubleArray -> a.minOrNull() to a.maxOrNull(); else -> null to null }
                if (lo == null || hi == null || lo.isNaN()) continue
                t.check(lo >= -STOCK_BELOW_ZERO * max(hi, 0.0), "stock ${s.name}" + if (s.arrays.size > 1) " (table ${i + 1})" else "") { "a place at ${fmt(lo)}" }
            }
        }
        // Shares: green, drinking water within reach, how well fed.
        fun share(name: String, a: FloatArray, hi: Double = 1.0) {
            var out = 0; var at = -1
            for (p in a.indices) if (!(a[p] >= 0f && a[p] <= hi)) { out++; if (at < 0) at = p }
            t.check(out == 0, name) { "outside 0..%s in %d places, first place %d (%s)".format(fmt(hi), out, at, a[at]) }
        }
        share("green forage", land.plants.green)
        share("drinking water within reach", land.drink.water)
        for ((k, s) in fauna.kinds.withIndex()) share("resident ${s.name} fed", fauna.fed[k], FED_MAX)
        // The weather in each place.
        fun range(name: String, a: FloatArray, lo: Double, hi: Double, unit: String) {
            var out = 0; var at = -1
            for (p in a.indices) if (!(a[p] >= lo && a[p] <= hi)) { out++; if (at < 0) at = p }
            t.check(out == 0, name) { "outside %s..%s %s in %d places, first place %d (%s)".format(fmt(lo), fmt(hi), unit, out, at, a[at]) }
        }
        range("temperature", sim.weather.temperature, PLACE_TEMPERATURE_MIN, PLACE_TEMPERATURE_MAX, "°C")
        range("snow lying", sim.weather.snowpack, 0.0, PLACE_SNOW_MAX_MM, "mm")
        // Residents, all kinds together, in each place (the viewer's "Resident herds (all)").
        run {
            var out = 0; var at = -1; var most = 0.0
            for (p in 0 until n) {
                var kg = 0.0
                for ((k, s) in fauna.kinds.withIndex()) kg += (fauna.adults[k][p] + fauna.young[k][p]) * s.massKg.toDouble()
                if (kg / 1000 > RESIDENTS_MAX_T_KM2) { out++; if (kg > most) { most = kg; at = p } }
            }
            t.check(out == 0, "residents") { "over %s t/km² in %d places, most %s t/km² at place %d".format(fmt(RESIDENTS_MAX_T_KM2), out, fmt(most / 1000), at) }
        }
        // Herds, packs and bands: sound numbers, in a place of the land, fed within 0..1, stores not below zero.
        run {
            var bad = 0; var first: String? = null
            for (g in sim.groups.groups) {
                if (!g.alive) continue
                val why = when {
                    g.young < 0 -> "young ${g.young}"
                    g.place.i !in 0 until n -> "at ${g.place}"
                    !(g.fed >= 0f && g.fed <= FED_MAX) -> "fed ${g.fed}"
                    g.stores.a.any { !(it >= 0f) } -> "stores " + g.stores.a.joinToString(",")
                    else -> null
                }
                if (why != null) { bad++; if (first == null) first = "${g.species.name} #${g.id}: $why" }
            }
            t.check(bad == 0, "groups") { "$bad groups unsound, first $first" }
        }
        // The books (audit=true): what no process explains, against the stock.
        sim.books?.let { b ->
            val stocks = sim.stocks().list
            for (stuff in inflation.Books.Stuff.entries) {
                val total = stocks.filter { it.stuff == stuff }.sumOf { it.sum() }
                val left = b.partNames.sumOf { abs(b.leftOver(it, stuff)) }
                t.check(left <= BOOKS_LEFT_OVER * total, "books, ${stuff.name.lowercase()}") { "%s %s left over by the parts, against a stock of %s".format(fmt(left * stuff.scale), stuff.unit, fmt(total * stuff.scale)) }
            }
        }
        return Result(t.findings, t.tried, "state at the end, ${sim.weather.date}")
    }

    /** The tables of numbers [part] keeps (as the stocks' test finds them): fields holding arrays of floats or doubles, or arrays of those. */
    private fun tables(part: Any): List<Pair<String, Any>> {
        val out = ArrayList<Pair<String, Any>>()
        var c: Class<*>? = part.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val name = "${part.javaClass.simpleName}.${f.name}"
                when (val v = f.get(part)) {
                    is FloatArray, is DoubleArray -> out += name to v
                    is Array<*> -> v.withIndex().forEach { (i, a) -> if (a is FloatArray || a is DoubleArray) out += "$name[$i]" to a }
                }
            }
            c = c.superclass
        }
        return out
    }

    private fun fmt(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == 0.0 -> "0"
        abs(v) >= 1e5 || abs(v) < 1e-3 -> "%.3g".format(v)
        abs(v) >= 100 -> "%.0f".format(v)
        abs(v) >= 10 -> "%.1f".format(v)
        else -> "%.3g".format(v)
    }
}
