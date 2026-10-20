package inflation.run

import inflation.KindStock
import inflation.StockName
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
 * The limits are what nature shows (Olli, 2026-10-19: first lax, then from reference values in nature), each with its
 * source: a finding says the run left what real land, plants and animals do, not that it is wrong in detail. Years are
 * whole years from the history's first week; a year cut short at the end is left out.
 */
object Nominal {
    // ---- the limits
    // (Set from what nature shows, with the source; "from memory" where the figure is recalled, not looked up. The run
    // histories they were checked against: storm-1..2 3 years, silt-1..3 20 years on main.)

    /**
     * How well fed a plant-eating kind may be: at most fully (with float slack), and over a whole year at least this. A
     * year short by more than this cannot be carried: an ungulate's fat keeps it some weeks at most (Parker et al. 2009,
     * from memory), so a kind living a year on 70% of its need is starving, not wintering.
     */
    const val FED_MAX = 1.001
    const val FED_YEAR_MIN = 0.7
    /**
     * The land's mean temperature, °C, in any week and over a year: land between some 30° and 45° N, from the coast to
     * the high mountains, keeps a yearly mean of about 5–20 °C (the runs: 9–12 °C), and a week's mean over all of it
     * well within -25..35 °C (the runs: -7..24 °C).
     */
    const val TEMPERATURE_WEEK_MIN = -25.0; const val TEMPERATURE_WEEK_MAX = 35.0
    const val TEMPERATURE_YEAR_MIN = 0.0; const val TEMPERATURE_YEAR_MAX = 22.0
    /**
     * Rain on the land, mm: the mean over all of it in any week, and its sum over a year. The lands about the
     * Mediterranean and the Near East take some 300–900 mm a year on average (the runs: 490–700); a week of over
     * 120 mm on all the land at once has no match there.
     */
    const val RAIN_WEEK_MAX = 120.0
    const val RAIN_YEAR_MIN = 150.0; const val RAIN_YEAR_MAX = 1500.0
    /**
     * The land's mean snow lying, mm of water: under 2 m, as if a twentieth of the land lay under 40 m of glacier ice
     * (the runs: 25–630 mm); the soil's water, under the deepest soil the model has (220 mm, [inflation.world.HexHydrology.soilCap]).
     */
    const val SNOW_MAX_MM = 2000.0
    const val SOIL_WATER_MAX_MM = 250.0
    /** Areas (the land's, each kind of country's) may change by this share over a run: they should not change at all. */
    const val AREA_DRIFT = 1e-4
    /**
     * Plants over the whole land, kg/m² dry: the world's land holds some 7 (about 450 Gt of carbon in plants on
     * 130 million km²: Erb et al. 2018), drier lands less (the runs: 3.5–8); and in any kind of country, under the
     * richest temperate forests' 60.
     */
    const val PLANTS_MIN_KG_M2 = 1.0; const val PLANTS_MAX_KG_M2 = 20.0
    const val BIOME_PLANTS_MAX_KG_M2 = 60.0
    /**
     * All big animals (residents, packs and bands) over the land, t/km², at most what the land's rain carries:
     * large herbivores' biomass in African savannas rises with the rain as log10(kg/km²) = 1.685 log10(mm) - 0.537
     * (Coe, Cumming & Phillipson 1976), some 14 t/km² at 600 mm; the richest of all are no richer (the runs: 0.8–1.9).
     */
    fun animalsMaxTKm2(rainMm: Double) = Math.pow(10.0, 1.685 * Math.log10(rainMm) - 0.537) / 1000
    /** People over the land, per km²: foragers live at 0.01–0.5 (Binford 2001), above it only on rich coasts and rivers (the runs: 0.015–0.06). */
    const val PEOPLE_MAX_KM2 = 0.5
    /**
     * Young born to a kind in a year, per head it numbered on average: the most fecund here, wild boar, wolves and
     * jackals, bear litters of 4–6 a year to a breeding female, about 1 a head; the ungulates 0.2–0.5 (the runs: up to
     * 0.85, wolves).
     */
    const val BIRTHS_PER_HEAD = 1.2
    /**
     * A kind's heads must add up over each year: the heads it gained, against its young born less its deaths of every
     * cause, may differ by at most this share of its heads on average (heads that went extinct in a place, and the
     * rounding of whole animals in groups; the runs: within 0.06, but for the deaths booked beyond the capped odds
     * before model 2.17, up to 0.64).
     */
    const val DEATHS_BALANCE = 0.1
    /**
     * All big hunting kinds' biomass (packs, lone hunters and resident hunters, not people) against the plant-eaters':
     * 10 t of prey carries some 90 kg of each kind of carnivore that hunts it (Carbone & Gittleman 2002), so the
     * hunters together at most some 3%; the Serengeti's lions, hyenas, leopards and cheetahs weigh some 0.3% of its
     * plant-eaters (from memory, rough). Below 0.05% the hunters are as good as gone (the runs: 0.15–0.22%).
     */
    const val PREDATORS_MIN = 0.0005; const val PREDATORS_MAX = 0.03
    /**
     * Hunting kinds in groups, over a year: fed at least as the plant-eaters ([FED_YEAR_MIN]); at least half their
     * packs holding ground of their own (wolves: floaters are some 10–15% of a population, Mech & Boitani 2003, from
     * memory; the runs: 88–100%); and a kind of at least [HUNTERS_FLOOR] heads kills something every year.
     */
    const val HUNTERS_FED_YEAR_MIN = FED_YEAR_MIN
    const val HOLDING_MIN = 0.5
    const val HUNTERS_FLOOR = 20.0
    /**
     * The least the bands must keep, as the Natufians of the Levant had it (Olli, 2026-10-19: for now at least): every
     * band keeps fire (hearths in every Natufian site: Bar-Yosef 1998); spears and clothes for most of them (bone awls
     * and needles, and the cold winters of the hills; the start kit is spears for all, clothes for half); hunting known
     * and practised (gazelle the main game, Bar-Oz 2004, from memory), at least 0.3 of a master's level (a guess: bands
     * start at 0, and those that hunt week by week near 1 in a few years; the runs: 0.96); and meat and fish at least a
     * fifth of what they eat (foragers in warm lands get some 25–50% of their food from hunting: Cordain et al. 2000,
     * from memory; the runs: 40–45%). Shares of the bands' heads, over each year and at the end.
     */
    val PEOPLE_MIN = mapOf(HexHistory.PeopleWay.FIRE to 0.99, HexHistory.PeopleWay.SPEARS to 0.8, HexHistory.PeopleWay.CLAD to 0.8,
        HexHistory.PeopleWay.HUNTING to 0.3, HexHistory.PeopleWay.GAME to 0.2)

    /**
     * How a state may change from year to year (rise to at most [rise] times, fall to no less than 1/[fall]) and from
     * the first whole year to the last ([run] times either way), once it is at least [floor] in either year (a few
     * animals or tonnes rise and fall too much by chance to judge by).
     */
    class Drift(val rise: Double, val fall: Double, val run: Double, val floor: Double)
    /**
     * A kind's numbers. They can grow at most at their kind's highest rate, r = 1.5 W^-0.36 a year for W kg
     * (Caughley & Krebs 1983), with half again for the scatter about that line; people at most some 3% a year
     * (foragers: Hassan 1981, from memory). A hard winter can take two thirds of a herd (Soay sheep: Clutton-Brock &
     * Pemberton 2004). Over a run, mild cycles and no kind more than 4 times up or down (Olli: no extinction).
     */
    fun animals(kind: String): Drift {
        val r = if (kind == Catalogue.HUMAN.name) 0.03 else 1.5 * Math.pow((Catalogue.all.firstOrNull { it.name == kind }?.massKg ?: 50f).toDouble(), -0.36)
        return Drift(rise = Math.exp(1.5 * r), fall = 3.0, run = 4.0, floor = 20.0)
    }
    /**
     * The slow stocks of plants and soil, thousand tonnes, over the whole land. Trees and the soil's organic matter
     * turn over in decades: a year changes them by a few per cent (the runs: 4% at most); litter and the soil's
     * animals follow the year's growth and warmth, shrubs their browsing; grass and herbs follow the rain, a dry year
     * growing half a wet one's (Knapp & Smith 2001; Sala et al. 1988).
     */
    val STOCKS = mapOf(
        StockName.PLANTS to Drift(rise = 1.1, fall = 1.1, run = 1.5, floor = 1.0), StockName.TREES to Drift(rise = 1.1, fall = 1.1, run = 1.5, floor = 1.0),
        StockName.SOIL_ORGANIC_MATTER to Drift(rise = 1.05, fall = 1.05, run = 1.5, floor = 1.0),
        StockName.LITTER to Drift(rise = 1.25, fall = 1.25, run = 2.0, floor = 1.0), StockName.SOIL_ANIMALS to Drift(rise = 1.25, fall = 1.25, run = 2.0, floor = 1.0),
        StockName.SHRUBS to Drift(rise = 1.25, fall = 1.25, run = 2.0, floor = 1.0), StockName.HERBS to Drift(rise = 2.0, fall = 2.0, run = 3.0, floor = 1.0),
    )
    /**
     * Every other pool of matter (thousand tonnes), of small life (tonnes or heads) and of water life: insects and
     * rodents swing many times over from year to year (vole cycles: Krebs 2013, from memory), so these are held only
     * against running away.
     */
    val POOLS = Drift(rise = 5.0, fall = 5.0, run = 20.0, floor = 0.01)
    val SMALL_AND_WATER_LIFE = Drift(rise = 5.0, fall = 5.0, run = 20.0, floor = 1.0)
    /** Snow lying (mm over the land): it must not pile up from year to year. */
    val SNOW = Drift(rise = 1.5, fall = 2.0, run = 2.0, floor = 1.0)

    /**
     * Residents of all kinds in any one place, t/km²: twice the richest savanna's whole-ecosystem 25 t/km² (Coe et al.
     * 1976 at 1000 mm), for a season's crowding on the best ground.
     */
    const val RESIDENTS_MAX_T_KM2 = 50.0
    /** Any place's temperature over a week, °C: the coldest and hottest weeks on Earth's land are near -60 and 45 °C. */
    const val PLACE_TEMPERATURE_MIN = -60.0; const val PLACE_TEMPERATURE_MAX = 50.0
    /** What a table of a stock may hold below zero, as a share of its largest value (float rounding). */
    const val STOCK_BELOW_ZERO = 1e-6
    /** Tables in which NaN means "none" by design, so only an infinity in them is out of limits: the odds of arriving, NaN where a kind cannot stay ([inflation.ecology.HexFauna.arrive]). */
    val NAN_MEANS_NONE = setOf("HexFauna.arrive")
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
            for (i in y * w until min((y + 1) * w, s.size)) { val v = s[i]; if (!v.isNaN()) { sum += v; c++ } }
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
                // (Each kind's numbers.)
                Key.animalKind(k) != null -> {
                    val kind = Key.animalKind(k)!!
                    val d = animals(kind)
                    drift(t, k, years, ::yearly, d, gone = true)
                    // Present in the first week and none in the last: gone (not judged by the year's mean, which a kind dying out in its last weeks keeps above 0).
                    val last = (s.size - 1 downTo first).firstOrNull { !s[it].isNaN() }
                    if (last != null && last > first) t.check(!(s[first] > 0f && s[last] <= 0f), k) { "gone by the end (%s at the start)".format(fmt(s[first].toDouble())) }
                    if (kind == Catalogue.HUMAN.name && area > 0) eachYear(0.0, PEOPLE_MAX_KM2, "people per km²", 1 / area)
                    for (y in 0 until years) {
                        val heads = yearly(k, y)
                        if (!(heads >= d.floor)) continue
                        val born = yearly(Key.births(kind), y)
                        if (!born.isNaN()) t.check(born <= BIRTHS_PER_HEAD * heads, k) { "%s born in year %d of %s heads on average, over %s a head".format(fmt(born), y + 1, fmt(heads), fmt(BIRTHS_PER_HEAD)) }
                    }
                }
                Key.isBiomass(k) -> {
                    val name = k.substringAfter('/')
                    // (A kind's residents and groups are judged by its head count: one living the other way for a while is no fault.)
                    if (KindStock.isKind(name)) continue
                    val stock = StockName.of(name)
                    // (Fast pools may empty for a year, as the locusts between plagues; the slow stocks may not.)
                    drift(t, k, years, ::yearly, STOCKS[stock] ?: POOLS, gone = stock in STOCKS)
                    if (stock == StockName.PLANTS && area > 0) eachYear(PLANTS_MIN_KG_M2, PLANTS_MAX_KG_M2, "kg/m² of plants over the land", 1 / area)
                }
                Key.isSmallLife(k) || Key.isWaterLife(k) -> drift(t, k, years, ::yearly, SMALL_AND_WATER_LIFE, gone = true)
                Key.peopleWay(k) != null -> { weekly(0.0, 1.0, ""); eachYear(PEOPLE_MIN.getValue(Key.peopleWay(k)!!), 1.0, "of the bands over the year") }
                Key.huntersOf(k) == HexHistory.Hunters.FED -> eachYear(HUNTERS_FED_YEAR_MIN, Double.POSITIVE_INFINITY, "of its need got over the year")
                Key.huntersOf(k) == HexHistory.Hunters.HOLDING -> { weekly(0.0, 1.0, ""); eachYear(HOLDING_MIN, 1.0, "of packs holding ground over the year") }
                Key.huntersOf(k) == HexHistory.Hunters.KILLS -> {
                    val kind = Key.huntersKind(k)!!
                    for (y in 0 until years) if (yearly(Key.animals(kind), y) >= HUNTERS_FLOOR) t.check(yearly(k, y) > 0, k) { "killed nothing in year ${y + 1}" }
                }
            }
        }
        // Heads that add up: what a kind gained in a year against its young born less its dead, year-end to year-end
        // (the first year from its first week, whose births and deaths came before it was counted).
        run {
            val deathKeys = HashMap<String, MutableList<String>>()
            for (k in h.series.keys) Key.deathsKind(k)?.let { deathKeys.getOrPut(it) { ArrayList() } += k }
            for ((kind, dk) in deathKeys) {
                val heads = h.series[Key.animals(kind)] ?: continue
                val born = h.series[Key.births(kind)]
                fun sum(s: HexHistory.FloatList?, from: Int, to: Int): Double { var v = 0.0; if (s != null) for (i in from until min(to, s.size)) { val x = s[i]; if (!x.isNaN()) v += x }; return v }
                for (y in 0 until years) {
                    val from = max(1, y * w); val to = (y + 1) * w
                    if (to > heads.size) continue
                    val h0 = heads[from - 1].toDouble(); val h1 = heads[to - 1].toDouble(); val mean = yearly(Key.animals(kind), y)
                    if (h0.isNaN() || h1.isNaN() || !(mean >= animals(kind).floor)) continue
                    val b = sum(born, from, to); val d = dk.sumOf { sum(h.series[it], from, to) }
                    val off = (h1 - h0) - (b - d)
                    t.check(abs(off) <= DEATHS_BALANCE * mean, "deaths of $kind") {
                        "year %d: heads %s to %s, born %s, died %s: %s heads %s, over %s of its %s".format(y + 1, fmt(h0), fmt(h1), fmt(b), fmt(d), fmt(abs(off)),
                            if (off > 0) "more dead than lost" else "lost beyond the dead", fmt(DEATHS_BALANCE), fmt(mean))
                    }
                }
            }
        }
        // The hunters against the hunted, by biomass.
        run {
            val kinds = Catalogue.all.filter { !it.human && Key.animals(it.name) in h.series }
            // (Only in a world that had hunters: one without them is a test of something else.)
            if (kinds.none { it.carnivore }) return@run
            for (y in 0 until years) {
                fun kg(carnivore: Boolean) = kinds.filter { it.carnivore == carnivore }.sumOf { s -> yearly(Key.animals(s.name), y).let { if (it.isNaN()) 0.0 else it * s.massKg } }
                val prey = kg(false)
                if (prey > 0) {
                    val share = kg(true) / prey
                    t.check(share in PREDATORS_MIN..PREDATORS_MAX, "hunters to plant-eaters") { "%s of the plant-eaters' biomass in year %d, outside %s..%s".format(fmt(share), y + 1, fmt(PREDATORS_MIN), fmt(PREDATORS_MAX)) }
                }
            }
        }
        // All big animals over the land, from the books' pools (thousand tonnes over km² is kt/km², times 1000 for t/km²).
        if (area > 0) {
            val pools = listOf(Key.biomass(StockName.RESIDENT_ANIMALS), Key.biomass(StockName.GROUPS)).filter { it in h.series }
            if (pools.isNotEmpty()) for (y in 0 until years) {
                val tKm2 = pools.sumOf { yearly(it, y).let { v -> if (v.isNaN()) 0.0 else v } } * 1000 / area
                val most = animalsMaxTKm2(yearly(Key.RAIN, y))
                if (!most.isNaN()) t.check(tKm2 <= most, "big animals") { "%s t/km² over the land in year %d, over the %s its rain carries".format(fmt(tKm2), y + 1, fmt(most)) }
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
            t.check(b / a in 1 / d.fall..d.rise, k) { "%s to %s from year %d to %d, beyond %s..%s times".format(fmt(a), fmt(b), y, y + 1, fmt(1 / d.fall), fmt(d.rise)) }
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
            val nanOk = name.substringBefore('[') in NAN_MEANS_NONE
            when (table) {
                is FloatArray -> for (i in table.indices) if (if (nanOk) table[i].isInfinite() else !table[i].isFinite()) { bad++; if (badAt < 0) badAt = i }
                is DoubleArray -> for (i in table.indices) if (if (nanOk) table[i].isInfinite() else !table[i].isFinite()) { bad++; if (badAt < 0) badAt = i }
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
        // Snow and ice no deeper than a glacier there can stand (deeper flows on: [inflation.world.HexHydrology.iceCapMm]).
        run {
            val snow = sim.weather.snowpack; val cap = land.hydro.iceCapMm
            var out = 0; var at = -1
            for (p in 0 until n) if (!(snow[p] >= 0f && snow[p] <= cap[p] * 1.001f)) { out++; if (at < 0) at = p }
            t.check(out == 0, "snow lying") { "outside 0..what a glacier there can stand in %d places, first place %d (%s mm against %s)".format(out, at, snow[at], cap[at]) }
        }
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
        // The bands' least (as the Natufians), and the packs holding ground.
        HexHistory.peopleWays(sim.groups)?.let { by ->
            for ((way, least) in PEOPLE_MIN) {
                val v = by[way.ordinal]
                if (!v.isNaN()) t.check(v >= least, "people, ${way.label}") { "%s of the bands' heads, under %s".format(fmt(v), fmt(least)) }
            }
        }
        for (s in sim.groups.kinds) if (s.pack) HexHistory.holdingGround(sim.groups, s)?.let { v ->
            t.check(v >= HOLDING_MIN, "${s.name}, packs holding ground") { "%s of its packs, under %s".format(fmt(v), fmt(HOLDING_MIN)) }
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
