package inflation.tools

import inflation.Args
import inflation.ecology.Fed
import inflation.ecology.get
import inflation.ecology.HexFauna
import inflation.ecology.HexFood
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Lifeway
import inflation.ecology.walkCost
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * A closer look at the land by the classes of the plenty check ([HexClasses]):
 *
 * - drinking water: the share of the land with lasting water (river, fresh lake, spring) within an easy
 *   walk, the mean walk to it, and the share that is humid country (where no springs are placed) with
 *   none; then week by week the share with water and the walk to it;
 * - forage: standing grass and browse, how green, what the residents and herds could take a week, what
 *   they took, and what was left, with the plant-eaters there, residents and herds apart, and the kinds;
 * - wild boar: month by month their heads, how well fed, how far over their usual density, what fed them
 *   (plants, straw, animal food, reserves) and the roots and fruit where they stand; and how well fed by
 *   how crowded;
 * - herds going hungry (fed under a half) against those fed, by class: their share, size, what keeps
 *   them off the food, their walking, and the herds and residents crowding the same place.
 *
 * Usage: hex-anomaly-check (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-anomaly-check (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", Date.DAYS_PER_YEAR / cfg.daysPerStep)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val hw = sim.hw; val land = sim.land; val fauna = sim.fauna; val n = land.n
    val hc = HexClasses(sim); val names = HexClasses.NAMES; val nc = names.size
    hc.print()
    val pc = IntArray(n) { hc.cls[hw.placeHex[it]] }
    fun pct(x: Double, y: Double) = if (y > 0) 100.0 * x / y else Double.NaN

    // Drinking water, as the land lies.
    run {
        val km2 = DoubleArray(nc); val lasting = DoubleArray(nc); val walk = DoubleArray(nc); val humidDry = DoubleArray(nc)
        val dr = land.drink
        for (p in 0 until n) {
            val c = pc[p]; if (c < 0) continue
            val ar = land.areaKm2[p].toDouble(); val h = hw.placeHex[p]
            val rain = (0 until 12).sumOf { hw.precip[it][h].toDouble() }; val pet = (0 until 12).sumOf { hw.pet[it][h].toDouble() }
            val l = minOf(1f, dr.nearRiver[p] + dr.nearLake[p] + dr.nearSpring[p])
            km2[c] += ar; lasting[c] += ar * l; walk[c] += ar * dr.waterKmMean[p]
            if (rain / maxOf(1.0, pet) > 0.65) humidDry[c] += ar * (1f - l)
        }
        println("\ndrinking water: share of the land with lasting water within ${HexDrinkingWaterKm} km, mean walk to it, and humid land (rain/PET > 0.65, no springs placed) with none")
        for (c in 0 until nc) if (km2[c] > 0) println("%-13s lasting %5.1f%%  walk %5.1f km  humid without %5.1f%%".format(names[c],
            pct(lasting[c], km2[c]), walk[c] / km2[c], pct(humidDry[c], km2[c])))
    }

    val boar = fauna.kinds.indexOfFirst { it.name == "wild boar" }
    // By class, area-weighted sums over the weeks.
    val km2W = DoubleArray(nc); val waterW = DoubleArray(nc); val walkW = DoubleArray(nc)
    val herbW = DoubleArray(nc); val shrubW = DoubleArray(nc); val greenW = DoubleArray(nc); val palW = DoubleArray(nc)
    val canW = DoubleArray(nc); val tookW = DoubleArray(nc); val leftW = DoubleArray(nc)
    val resKgW = DoubleArray(nc); val herdKgW = DoubleArray(nc)
    val kindsKg = Array(nc) { HashMap<String, Double>() }
    // Wild boar, by month.
    val bHeads = DoubleArray(12); val bFed = DoubleArray(12); val bOver = DoubleArray(12); val bRoots = DoubleArray(12); val bFruit = DoubleArray(12)
    val bFrom = Array(4) { DoubleArray(12) }; val bWeeks = IntArray(12)
    val byCrowd = DoubleArray(6); val byCrowdFed = DoubleArray(6)
    val bStart = if (boar >= 0) fauna.count(boar) else 0.0
    // Herds, hungry (0) and fed (1), by class.
    val gN = Array(2) { DoubleArray(nc) }; val gHeads = Array(2) { DoubleArray(nc) }; val gSnow = Array(2) { DoubleArray(nc) }
    val gAccess = Array(2) { DoubleArray(nc) }; val gTether = Array(2) { DoubleArray(nc) }; val gQual = Array(2) { DoubleArray(nc) }
    val gWalk = Array(2) { DoubleArray(nc) }; val gCrowd = Array(2) { DoubleArray(nc) }; val gRes = Array(2) { DoubleArray(nc) }
    val gLeft = Array(2) { DoubleArray(nc) }; val gKinds = Array(nc) { HashMap<String, Double>() }

    repeat(weeks) {
        sim.step()
        val w = sim.weather; val m = w.date.month
        val herdKgAt = DoubleArray(n)
        for (g in sim.groups.groups) if (g.alive && g.species.lifeway == Lifeway.HERD) herdKgAt[g.place.i] += g.size * g.species.massKg.toDouble()
        for (p in 0 until n) {
            val c = pc[p]; if (c < 0) continue
            val ar = land.areaKm2[p].toDouble()
            km2W[c] += ar; waterW[c] += ar * land.drink.water[p]; walkW[c] += ar * land.drink.waterKm[p]
            val pl = land.plants
            herbW[c] += ar * pl.herb[p]; shrubW[c] += ar * pl.shrub[p]; greenW[c] += ar * pl.green[p]; palW[c] += ar * land.palatable[p]
            val reach = HexFauna.REACH
            canW[c] += ar * (reach * land.palatable[p] * pl.herb[p] + pl.browse(p))
            // (What was taken: what could be, less what was left.)
            tookW[c] += ar * (reach * land.palatable[p] * pl.herb[p] + pl.browse(p) - fauna.grazeLeft[p] - fauna.browseLeft[p])
            leftW[c] += ar * (fauna.grazeLeft[p] + fauna.browseLeft[p])
            var res = 0.0
            for ((k, s) in fauna.kinds.withIndex()) {
                if (s.carnivore) continue
                val kg = (fauna.adults[k][p] + fauna.young[k][p]) * ar * s.massKg
                res += kg; if (kg > 0) kindsKg[c].merge(s.name, kg, Double::plus)
            }
            resKgW[c] += res; herdKgW[c] += herdKgAt[p]
        }
        for (g in sim.groups.groups) if (g.alive && g.species.lifeway == Lifeway.HERD) {
            val c = pc[g.place.i]; if (c >= 0) kindsKg[c].merge("${g.species.name} (herds)", g.size * g.species.massKg.toDouble(), Double::plus)
        }
        if (boar >= 0) {
            val s = fauna.kinds[boar]; val usual = HexFauna.usualDensity(s)
            bWeeks[m]++
            for (p in 0 until n) {
                val d = fauna.adults[boar][p] + fauna.young[boar][p]; if (d <= 0f) continue
                val hd = (d * land.areaKm2[p]).toDouble()
                bHeads[m] += hd; bFed[m] += hd * fauna.fed[boar][p]; bOver[m] += hd * d / usual
                bRoots[m] += hd * fauna.food.roots[p]; bFruit[m] += hd * fauna.food.fruit[p]
                val b = when { d < 0.5f * usual -> 0; d < usual -> 1; d < 2f * usual -> 2; d < 4f * usual -> 3; d < 8f * usual -> 4; else -> 5 }
                byCrowd[b] += hd; byCrowdFed[b] += hd * fauna.fed[boar][p]
            }
            val ff = fauna.fedFrom[boar]; val heads = ff[Fed.HEADS].sumThenReset().coerceAtLeast(1e-9)
            for ((i, f) in listOf(Fed.PLANTS, Fed.STRAW, Fed.ANIMAL, Fed.RESERVES).withIndex()) bFrom[i][m] += ff[f].sumThenReset() / heads
        }
        // Herds, after they ate.
        val headsAt = DoubleArray(n)
        for (g in sim.groups.groups) if (g.alive && g.species.lifeway == Lifeway.HERD) headsAt[g.place.i] += g.size.toDouble()
        for (g in sim.groups.groups) {
            if (!g.alive || g.species.lifeway != Lifeway.HERD) continue
            val p = g.place.i; val c = pc[p]; if (c < 0) continue
            val i = if (g.fed < 0.5f) 0 else 1; val s = g.species
            gN[i][c]++; gHeads[i][c] += g.size
            if (w.snowpack[p] > s.snowLimitMm) gSnow[i][c]++
            gAccess[i][c] += HexFood.access(s, land.crag[p]); gTether[i][c] += fauna.tether(s, p); gQual[i][c] += land.plants.nourishment(p)
            gWalk[i][c] += sim.groups.walkCost(g); gCrowd[i][c] += headsAt[p] / land.areaKm2[p]
            var res = 0.0; for ((k, r) in fauna.kinds.withIndex()) if (!r.carnivore) res += (fauna.adults[k][p] + fauna.young[k][p]) * r.massKg
            gRes[i][c] += res; gLeft[i][c] += fauna.grazeLeft[p] + fauna.browseLeft[p]
            if (i == 0) gKinds[c].merge(s.name, 1.0, Double::plus)
        }
    }

    println("\nweek by week: drinking water (share within reach), walk to it km; forage g/m²: standing grass and herbs, shrubs, green share, palatable;")
    println("  what could be taken a week, what was taken, what was left; plant-eaters kg/km², residents + herds")
    for (c in 0 until nc) if (km2W[c] > 0) { val a0 = km2W[c]
        println("%-13s water %.2f walk %5.1f | herb %6.0f shrub %6.0f green %.2f palatable %.2f | could %5.1f took %7.3f left %5.1f | residents %6.0f herds %6.0f".format(names[c],
            waterW[c] / a0, walkW[c] / a0, 1000 * herbW[c] / a0, 1000 * shrubW[c] / a0, greenW[c] / a0, palW[c] / a0,
            1000 * canW[c] / a0, 1000 * tookW[c] / a0, 1000 * leftW[c] / a0, resKgW[c] / a0, herdKgW[c] / a0))
        val all = kindsKg[c].values.sum()
        println("%13s by weight: ".format("") + kindsKg[c].entries.sortedByDescending { it.value }.take(7).joinToString(", ") { "%s %.0f%%".format(it.key, pct(it.value, all)) })
    }

    if (boar >= 0) {
        println("\nwild boar: %.0f at the start, %.0f at the end".format(bStart, fauna.count(boar)))
        println("month   heads   fed  x usual  roots g/m2 fruit g/m2 | fed from: plants straw animal reserves")
        for (m in 0 until 12) if (bWeeks[m] > 0 && bHeads[m] > 0) { val h = bHeads[m]; val k = bWeeks[m]
            println("%5d %8.0f  %.2f  %6.2f  %9.1f %9.1f | %.2f %.2f %.2f %.2f".format(m + 1, h / k, bFed[m] / h, bOver[m] / h,
                1000 * bRoots[m] / h, 1000 * bFruit[m] / h, bFrom[0][m] / k, bFrom[1][m] / k, bFrom[2][m] / k, bFrom[3][m] / k)) }
        val lab = listOf("<0.5", "0.5-1", "1-2", "2-4", "4-8", ">8")
        println("fed by density against usual: " + lab.indices.joinToString(", ") { b -> "%s x: %.0f%% of heads fed %.2f".format(lab[b], pct(byCrowd[b], byCrowd.sum()), byCrowdFed[b] / byCrowd[b].coerceAtLeast(1e-9)) })
    }

    println("\nherds hungry (fed < 0.5) against fed, by class: groups a week, mean size; snowed share, access (crags), tether (water), quality,")
    println("  walking cost; herd heads per km² in the same place; resident plant-eaters kg/km² there; forage left g/m²")
    for (c in 0 until nc) for (i in 0..1) if (gN[i][c] > 0) { val k = gN[i][c]
        println("%-13s %-6s %6.1f x %5.0f  snowed %.2f access %.2f tether %.2f quality %.2f walk %.2f  herds %6.1f/km2  residents %6.0f  left %5.1f".format(
            if (i == 0 || gN[0][c] <= 0) names[c] else "", if (i == 0) "hungry" else "fed", k / weeks, gHeads[i][c] / k, gSnow[i][c] / k, gAccess[i][c] / k, gTether[i][c] / k,
            gQual[i][c] / k, gWalk[i][c] / k, gCrowd[i][c] / k, gRes[i][c] / k, 1000 * gLeft[i][c] / k))
        if (i == 1 && gKinds[c].isNotEmpty()) { val all = gKinds[c].values.sum()
            println("%13s hungry kinds: ".format("") + gKinds[c].entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "%s %.0f%%".format(it.key, pct(it.value, all)) }) }
    }
}

private const val HexDrinkingWaterKm = 3
