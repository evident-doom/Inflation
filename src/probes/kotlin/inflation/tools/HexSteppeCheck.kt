package inflation.tools

import inflation.Config
import inflation.saturate
import inflation.ecology.Thresholds
import inflation.ecology.HexFauna
import inflation.ecology.roundOf
import inflation.ecology.get
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Date
import inflation.world.HexPlants
import inflation.world.LifeForm
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Whether the dry grass country (steppe and semi-desert) is the herds' feeding ground: over the last year
 * of a run, by kind of country (each place's chief one) and season, the forage it offers (grass within
 * reach, how green and nourishing, straw, how hard it is grazed); for each herd kind, how it judges each
 * kind of country (its place quality and the parts of it: food, ground, freshness, water, climate,
 * hunters), where its animals stand and how well fed they are there, where its pull points from where it
 * stands (the kind of country 40 km along the bearing), where its rounds lead, and its deaths by cause.
 * Run with and without nets= in the config to set the learned networks beside the hand-written choices.
 *
 * Usage: hex-steppe-check (as hex-fauna-check: --world NAME [--years N] [--replicate R], ...; default 2 years, the last one measured)
 */
fun main(args: Array<String>) {
    val options = FaunaCheckOptions.parse(if ("--years" in args) args else args + arrayOf("--years", "2"))
    val sim = options.start()
    val hw = sim.hw; val hex = sim.hex; val land = sim.land; val fauna = sim.fauna; val groups = sim.groups
    val n = fauna.n; val nh = hex.size
    val nb = Biome.entries.size
    val biomeOf = hw.placeBiome
    val hexBiome = IntArray(nh) { -1 }
    run {
        val a = Array(nh) { DoubleArray(nb) }
        for (p in 0 until n) a[hw.placeHex[p]][biomeOf[p]] += land.areaKm2[p].toDouble()
        for (h in 0 until nh) if (a[h].sum() > 0) hexBiome[h] = a[h].indices.maxBy { a[h][it] }
    }
    val area = DoubleArray(nb); for (p in 0 until n) area[biomeOf[p]] += land.areaKm2[p].toDouble()
    val landArea = area.sum()
    // The kinds of country shown: those with at least 1% of the land, wettest last.
    val shown = Biome.entries.indices.filter { area[it] >= 0.01 * landArea }
    fun bname(b: Int) = Biome.entries[b].name.lowercase().replace('_', ' ')
    val dry = setOf(Biome.STEPPE.ordinal, Biome.SEMI_DESERT.ordinal, Biome.DESERT.ordinal)
    val herds = groups.kinds.filter { !it.carnivore }
    val kindOf = groups.kinds.withIndex().associate { it.value to it.index }
    val perYear = Date.DAYS_PER_YEAR / options.cfg.daysPerStep
    val steps = options.years * perYear
    // Seasons by the model's month (0 = mid Dec - mid Jan).
    val seasonNames = listOf("winter", "spring", "summer", "autumn")
    fun season(m: Int) = ((m + 1) % 12) / 3
    // Forage by kind of country and season: area-weighted sums, and the area-weeks they are over.
    val fArea = Array(4) { DoubleArray(nb) }
    val fGrass = Array(4) { DoubleArray(nb) }; val fGreen = Array(4) { DoubleArray(nb) }; val fNour = Array(4) { DoubleArray(nb) }
    val fStraw = Array(4) { DoubleArray(nb) }; val fGrazed = Array(4) { DoubleArray(nb) }; val fCured = Array(4) { DoubleArray(nb) }
    // What a week's grazing can take without eating the place bare (as the feeding reckons it), kg/km²/day.
    val fSupply = Array(4) { DoubleArray(nb) }
    // What holds the grass: the stand against its ceiling, the ceiling after grazing to lawn and rooting, the year's
    // soil moisture the ceiling is set by (and its moisture term), the soil's water now, and while the sward grows.
    val gHerb = Array(4) { DoubleArray(nb) }; val gCap = Array(4) { DoubleArray(nb) }; val gCapGrazed = Array(4) { DoubleArray(nb) }
    val gMoist = Array(4) { DoubleArray(nb) }; val gMTerm = Array(4) { DoubleArray(nb) }; val gWet = Array(4) { DoubleArray(nb) }
    val gGrowWet = Array(4) { DoubleArray(nb) }; val gGrowA = Array(4) { DoubleArray(nb) }; val gPerennial = Array(4) { DoubleArray(nb) }
    val gCanopy = Array(4) { DoubleArray(nb) }
    // Per herd kind: quality and its parts by kind of country and season (area-weighted, once a month).
    class Judge { val a = Array(4) { DoubleArray(nb) }; val q = Array(4) { DoubleArray(nb) }; val food = Array(4) { DoubleArray(nb) }
        val ground = Array(4) { DoubleArray(nb) }; val fresh = Array(4) { DoubleArray(nb) }; val tether = Array(4) { DoubleArray(nb) }
        val fit = Array(4) { DoubleArray(nb) }; val hunt = Array(4) { DoubleArray(nb) } }
    // Per herd kind: animal-weeks, fed and protein by kind of country and season; the pull from where they stand: animal-weeks
    // by the kind of country 40 km along it, weighed by its strength; rounds: herd-weeks on a round by the round place's kind of country.
    class Stand { val heads = Array(4) { DoubleArray(nb) }; val fed = Array(4) { DoubleArray(nb) }; val prot = Array(4) { DoubleArray(nb) }
        val pullTo = Array(4) { DoubleArray(nb) }; val pullFromDry = Array(4) { DoubleArray(nb) }; val pullW = DoubleArray(4); val pullFromDryW = DoubleArray(4)
        val pullAll = Array(4) { DoubleArray(nb) }; val pullAllW = DoubleArray(4)
        val roundTo = Array(4) { DoubleArray(nb) }; val roundN = DoubleArray(4); val herdWeeks = DoubleArray(4)
        val roundQ = DoubleArray(4); val hereQ = DoubleArray(4) }
    val judge = herds.associateWith { Judge() }; val stand = herds.associateWith { Stand() }
    val startCount = HashMap<String, Int>(); val endCount = HashMap<String, Int>()
    // Deaths and births over the measured year (the groups' tallies are for the week only).
    val deathsYear = HashMap<String, Int>(); val bornYear = HashMap<String, Int>()
    val b = groups.bearings
    val pullKm = 40.0
    fun pullTarget(k: Int, h: Int): Int {
        val e = b.east[k][h]; val s = b.south[k][h]; val len = hypot(e, s)
        if (len < 0.05f) return -1
        val (x, y) = hex.centreKm(h)
        val t = hex.hexAt(x + pullKm * e / len, y + pullKm * s / len)
        return if (t in 0 until nh) hexBiome[t] else -1
    }
    var lastMonth = -1
    val t0 = System.nanoTime()
    repeat(steps) { w ->
        sim.step()
        val measuring = w >= steps - perYear
        if (!measuring) return@repeat
        if (w == steps - perYear) {
            for (s in herds) startCount[s.name] = groups.groups.filter { it.alive && it.species == s }.sumOf { it.size }
        }
        for ((k, v) in groups.stats.deaths) deathsYear.merge(k, v, Int::plus)
        for ((k, v) in groups.stats.births) bornYear.merge(k, v, Int::plus)
        val m = b.month; val se = season(m)
        // Forage, each week.
        for (p in 0 until n) {
            val bi = biomeOf[p]; val a = land.areaKm2[p].toDouble()
            fArea[se][bi] += a
            fGrass[se][bi] += a * land.palatable[p] * land.plants.herb[p]
            fGreen[se][bi] += a * land.plants.green[p]; fNour[se][bi] += a * land.plants.nourishment(p)
            fStraw[se][bi] += a * land.plants.straw[p]; fGrazed[se][bi] += a * land.plants.grazing[p]
            val reach = HexFauna.REACH
            fSupply[se][bi] += a * reach * land.palatable[p] * land.plants.herb[p] * 1e6 / 7
            fCured[se][bi] += a * (if (land.plants.cured[p]) 1.0 else 0.0)
            val pl = land.plants; val cap = pl.ceiling(LifeForm.HERB, p)
            val lawn = 1f - exp(-pl.grazing[p] / HexPlants.LAWN_SCALE); val rooted = 1f - exp(-pl.rooting[p] / HexPlants.ROOT_SCALE)
            gHerb[se][bi] += a * pl.herb[p]; gCap[se][bi] += a * cap; gCapGrazed[se][bi] += a * cap * (1f - 0.5f * lawn - 0.2f * rooted)
            gMoist[se][bi] += a * pl.moisture[p]
            gMTerm[se][bi] += a * ((pl.moisture[p] - LifeForm.HERB.moistureMin) / (LifeForm.HERB.moistureFull - LifeForm.HERB.moistureMin)).coerceIn(0f, 1f)
            val wet = sim.land.hydro.soil[p] / sim.land.hydro.soilCap[p]
            gWet[se][bi] += a * wet; gPerennial[se][bi] += a * pl.perennialShare[p]; gCanopy[se][bi] += a * pl.canopy(p)
            if (pl.growthWarmth(p, sim.weather.temperature[p]) > 0f && !pl.cured[p]) { gGrowWet[se][bi] += a * wet; gGrowA[se][bi] += a }
        }
        // How each kind judges the land, once a month.
        if (m != lastMonth) {
            lastMonth = m
            for (s in herds) {
                val j = judge.getValue(s)
                for (p in 0 until n) {
                    val bi = biomeOf[p]; val a = land.areaKm2[p].toDouble()
                    val ground = min(1.5f, fauna.suits(s, p)) / 1.5f
                    val hunters = groups.monthHunterKg[p] / land.areaKm2[p]
                    j.a[se][bi] += a; j.q[se][bi] += a * groups.herdQuality(s, p, m)
                    j.food[se][bi] += a * saturate(groups.foodFor(s, p), Thresholds.FOOD_HALF); j.ground[se][bi] += a * ground
                    j.fresh[se][bi] += a * groups.freshness(s, p); j.tether[se][bi] += a * fauna.tether(s, p)
                    j.fit[se][bi] += a * fauna.monthFit(s, p, m)
                    j.hunt[se][bi] += a * (1f - 0.5f * hunters / (hunters + land.tuning.huntersHalfKg))
                }
                // The pull over all land of the kind's own country (where the kind is likely to be: any land), for reference.
                val k = kindOf.getValue(s); val st = stand.getValue(s)
                for (h in 0 until nh) {
                    val from = hexBiome[h]; if (from < 0) continue
                    val t = pullTarget(k, h); if (t < 0) continue
                    val str = hypot(b.east[k][h], b.south[k][h]).toDouble()
                    if (from in dry) { st.pullAll[se][t] += str; st.pullAllW[se] += str }
                }
            }
        }
        // Where the herds stand, how fed, where their pull and rounds lead.
        for (g in groups.groups) {
            if (!g.alive || g.species.carnivore) continue
            val st = stand[g.species] ?: continue
            val k = kindOf.getValue(g.species)
            val pl = g.place.i; val bi = biomeOf[pl]; val hd = g.size.toDouble()
            st.heads[se][bi] += hd; st.fed[se][bi] += hd * g.fed; st.prot[se][bi] += hd * g.protein
            val h = hw.placeHex[pl]
            val t = pullTarget(k, h)
            if (t >= 0) {
                val str = hd * hypot(b.east[k][h], b.south[k][h])
                st.pullTo[se][t] += str; st.pullW[se] += str
                if (bi in dry) { st.pullFromDry[se][t] += str; st.pullFromDryW[se] += str }
            }
            st.herdWeeks[se] += 1.0
            val r = groups.roundOf(g, m)
            if (r != null) {
                st.roundN[se] += 1.0; st.roundTo[se][biomeOf[r.place]] += 1.0
                st.roundQ[se] += groups.herdQuality(g.species, r.place, m); st.hereQ[se] += groups.herdQuality(g.species, pl, m)
            }
        }
    }
    for (s in herds) endCount[s.name] = groups.groups.filter { it.alive && it.species == s }.sumOf { it.size }

    println("steppe check: %d years, the last measured, in %.0f s; %s".format(options.years, (System.nanoTime() - t0) / 1e9,
        if (Config.string("nets") != null) "learned networks (nets=${Config.string("nets")})" else "hand-written choices"))
    println("seasons by the model's month: winter 11-1, spring 2-4, summer 5-7, autumn 8-10 (0 = mid Dec - mid Jan)")
    println()
    println("land by kind of country (each place's chief one): " + shown.joinToString(", ") { "%s %.1f%%".format(bname(it), 100 * area[it] / landArea) })
    println()
    println("== forage by kind of country and season: grass within reach (palatable herbs) kg/m², green 0..1, nourishment, cured share, straw g/m², grazing kg/km²/day of the grass a week's grazing may take (kg/km²/day)")
    for (bi in shown) {
        println("  %-24s".format(bname(bi)) + (0 until 4).joinToString("  |  ") { se ->
            val a = fArea[se][bi].coerceAtLeast(1e-9)
            "%s grass %.3f green %.2f nour %.2f cured %.2f straw %.1f grazed %.1f of %.0f".format(seasonNames[se].take(3), fGrass[se][bi] / a, fGreen[se][bi] / a,
                fNour[se][bi] / a, fCured[se][bi] / a, 1000 * fStraw[se][bi] / a, fGrazed[se][bi] / a, fSupply[se][bi] / a)
        })
    }
    println()
    println("months, normal (°C, area mean): " + shown.joinToString(", ") { bi ->
        var a = 0.0; var c = 0.0; var w = 0.0
        for (p in 0 until n) if (biomeOf[p] == bi) { val x = land.areaKm2[p].toDouble(); a += x
            c += x * (0 until 12).minOf { hw.placeTemp(p, it) }; w += x * (0 until 12).maxOf { hw.placeTemp(p, it) } }
        "%s coldest %.1f warmest %.1f".format(bname(bi), c / a, w / a)
    })
    println("== what holds the grass, by kind of country and season: herbs g/m² against their ceiling, and the ceiling after grazing to lawn and rooting; the year's soil moisture the ceiling is set by and its moisture term; the soil's water now (of what it holds), and in the weeks the sward is growing (share of the area-weeks); perennial share; tree canopy")
    for (bi in shown) {
        println("  %-24s".format(bname(bi)) + (0 until 4).joinToString("  |  ") { se ->
            val a = fArea[se][bi].coerceAtLeast(1e-9); val ga = gGrowA[se][bi]
            "%s herb %.0f cap %.0f grazed cap %.0f moist %.2f m %.2f wet %.2f growing %s (%.0f%%) per %.2f canopy %.2f".format(seasonNames[se].take(3),
                1000 * gHerb[se][bi] / a, 1000 * gCap[se][bi] / a, 1000 * gCapGrazed[se][bi] / a, gMoist[se][bi] / a, gMTerm[se][bi] / a, gWet[se][bi] / a,
                if (ga > 0) "%.2f".format(gGrowWet[se][bi] / ga) else "-", 100 * ga / a, gPerennial[se][bi] / a, gCanopy[se][bi] / a)
        })
    }
    for (s in herds) {
        val st = stand.getValue(s); val j = judge.getValue(s)
        val total = st.heads.sumOf { it.sum() }
        println()
        println("== ${s.name} (diet grass %.2f browse %.2f; animals %d -> %d over the measured year)".format(s.diet.grass, s.diet.browse,
            startCount[s.name] ?: 0, endCount[s.name] ?: 0))
        if (total <= 0) { println("   none"); continue }
        println("   where they stand, share of animal-weeks (fed, protein) by season; land share for reference")
        for (bi in shown) {
            val any = (0 until 4).any { st.heads[it][bi] > 0 }
            println("     %-24s land %4.1f%%  ".format(bname(bi), 100 * area[bi] / landArea) + (0 until 4).joinToString("  ") { se ->
                val hs = st.heads[se].sum().coerceAtLeast(1e-9); val h = st.heads[se][bi]
                if (!any) "%s     -          ".format(seasonNames[se].take(3))
                else if (h <= 0) "%s %5.1f%%          ".format(seasonNames[se].take(3), 0.0)
                else "%s %5.1f%% (%.2f %.2f)".format(seasonNames[se].take(3), 100 * h / hs, st.fed[se][bi] / h, st.prot[se][bi] / h)
            })
        }
        println("   how it judges each kind of country, by season: place quality (= sat.(food x ground) x freshness x water x climate x hunters); food alone (sat.), ground fit (suits, capped at 1.5, /1.5), freshness, water (tether), climate fit, hunters factor")
        for (bi in shown) {
            println("     %-24s".format(bname(bi)) + (0 until 4).joinToString("  |  ") { se ->
                val a = j.a[se][bi].coerceAtLeast(1e-9)
                "%s q %.3f food %.2f grd %.2f fresh %.2f wat %.2f clim %.2f hunt %.2f".format(seasonNames[se].take(3), j.q[se][bi] / a, j.food[se][bi] / a,
                    j.ground[se][bi] / a, j.fresh[se][bi] / a, j.tether[se][bi] / a, j.fit[se][bi] / a, j.hunt[se][bi] / a)
            })
        }
        fun mix(x: DoubleArray, wsum: Double) = if (wsum <= 0) "-" else
            x.indices.filter { x[it] > 0.02 * wsum }.sortedByDescending { x[it] }.joinToString(", ") { "%s %.0f%%".format(bname(it), 100 * x[it] / wsum) }
        println("   where the pull points ($pullKm km along it), by the strength-weighted animal-weeks:")
        for (se in 0 until 4) {
            println("     %s from where they stand: %s".format(seasonNames[se], mix(st.pullTo[se], st.pullW[se])))
            println("     %s from dry country they stand in: %s".format(" ".repeat(seasonNames[se].length), mix(st.pullFromDry[se], st.pullFromDryW[se])))
            println("     %s from all dry country (steppe, semi-desert, desert hexes, unweighted by animals): %s".format(" ".repeat(seasonNames[se].length), mix(st.pullAll[se], st.pullAllW[se])))
        }
        println("   rounds: share of herd-weeks on a round; the round's place this month by kind of country; its quality vs where the herd stands")
        for (se in 0 until 4) {
            val rn = st.roundN[se]
            println("     %-6s on a round %3.0f%%; round place: %s; quality there %.3f, where they stand %.3f".format(seasonNames[se],
                100 * rn / st.herdWeeks[se].coerceAtLeast(1.0), mix(st.roundTo[se], rn),
                st.roundQ[se] / rn.coerceAtLeast(1.0), st.hereQ[se] / rn.coerceAtLeast(1.0)))
        }
        val mean = ((startCount[s.name] ?: 0) + (endCount[s.name] ?: 0)) / 2.0
        val d = deathsYear.filterKeys { it.startsWith("${s.name}:") }.filterValues { it > 0 }
        val born = bornYear.filterKeys { it == s.name || it.startsWith("${s.name}:") || it.startsWith("${s.name} ") }.values.sum()
        println("   born over the measured year per 100 animals: %.1f".format(100 * born / mean.coerceAtLeast(1.0)))
        println("   deaths over the measured year per 100 animals (mean of start and end): " + d.entries.sortedByDescending { it.value }.take(8)
            .joinToString(", ") { "%s %.1f".format(it.key.substringAfter(':'), 100 * it.value / mean.coerceAtLeast(1.0)) })
    }
}
