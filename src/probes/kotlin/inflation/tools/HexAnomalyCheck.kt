package inflation.tools

import inflation.Args
import inflation.ecology.Fed
import inflation.ecology.get
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
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
 * - forage: standing grass and browse, how green, what the residents could take a week, what they took,
 *   and what was left, with the plant-eaters there and the kinds;
 * - wild boar: month by month their heads, how well fed, how far over their usual density, what fed them
 *   (plants, animal food, reserves) and the roots and fruit where they stand; and how well fed by
 *   how crowded.
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
    val resKgW = DoubleArray(nc)
    val kindsKg = Array(nc) { HashMap<String, Double>() }
    // Wild boar, by month.
    val bHeads = DoubleArray(12); val bFed = DoubleArray(12); val bOver = DoubleArray(12); val bRoots = DoubleArray(12); val bFruit = DoubleArray(12)
    val bFrom = Array(3) { DoubleArray(12) }; val bWeeks = IntArray(12)
    val byCrowd = DoubleArray(6); val byCrowdFed = DoubleArray(6)
    val bStart = if (boar >= 0) fauna.count(boar) else 0.0

    repeat(weeks) {
        sim.step()
        val w = sim.weather; val m = w.date.month
        for (p in 0 until n) {
            val c = pc[p]; if (c < 0) continue
            val ar = land.areaKm2[p].toDouble()
            km2W[c] += ar; waterW[c] += ar * land.drink.water[p]; walkW[c] += ar * land.drink.waterKm[p]
            val pl = land.plants
            herbW[c] += ar * pl.herb[p]; shrubW[c] += ar * pl.shrub[p]; greenW[c] += ar * pl.green[p]; palW[c] += ar * land.palatable[p]
            val reach = HexFauna.REACH
            canW[c] += ar * (reach * land.palatable[p] * pl.herb[p] + pl.browse(inflation.ecology.Place(p)))
            // (What was taken: what could be, less what was left.)
            tookW[c] += ar * (reach * land.palatable[p] * pl.herb[p] + pl.browse(inflation.ecology.Place(p)) - fauna.grazeLeft[p] - fauna.browseLeft[p])
            leftW[c] += ar * (fauna.grazeLeft[p] + fauna.browseLeft[p])
            var res = 0.0
            for ((k, s) in fauna.kinds.withIndex()) {
                if (s.carnivore) continue
                val kg = (fauna.adults[k][p] + fauna.young[k][p]) * ar * s.massKg
                res += kg; if (kg > 0) kindsKg[c].merge(s.name, kg, Double::plus)
            }
            resKgW[c] += res
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
            for ((i, f) in listOf(Fed.PLANTS, Fed.ANIMAL, Fed.RESERVES).withIndex()) bFrom[i][m] += ff[f].sumThenReset() / heads
        }
    }

    println("\nweek by week: drinking water (share within reach), walk to it km; forage g/m²: standing grass and herbs, shrubs, green share, palatable;")
    println("  what could be taken a week, what was taken, what was left; plant-eaters kg/km²")
    for (c in 0 until nc) if (km2W[c] > 0) { val a0 = km2W[c]
        println("%-13s water %.2f walk %5.1f | herb %6.0f shrub %6.0f green %.2f palatable %.2f | could %5.1f took %7.3f left %5.1f | residents %6.0f".format(names[c],
            waterW[c] / a0, walkW[c] / a0, 1000 * herbW[c] / a0, 1000 * shrubW[c] / a0, greenW[c] / a0, palW[c] / a0,
            1000 * canW[c] / a0, 1000 * tookW[c] / a0, 1000 * leftW[c] / a0, resKgW[c] / a0))
        val all = kindsKg[c].values.sum()
        println("%13s by weight: ".format("") + kindsKg[c].entries.sortedByDescending { it.value }.take(7).joinToString(", ") { "%s %.0f%%".format(it.key, pct(it.value, all)) })
    }

    if (boar >= 0) {
        println("\nwild boar: %.0f at the start, %.0f at the end".format(bStart, fauna.count(boar)))
        println("month   heads   fed  x usual  roots g/m2 fruit g/m2 | fed from: plants animal reserves")
        for (m in 0 until 12) if (bWeeks[m] > 0 && bHeads[m] > 0) { val h = bHeads[m]; val k = bWeeks[m]
            println("%5d %8.0f  %.2f  %6.2f  %9.1f %9.1f | %.2f %.2f %.2f".format(m + 1, h / k, bFed[m] / h, bOver[m] / h,
                1000 * bRoots[m] / h, 1000 * bFruit[m] / h, bFrom[0][m] / k, bFrom[1][m] / k, bFrom[2][m] / k)) }
        val lab = listOf("<0.5", "0.5-1", "1-2", "2-4", "4-8", ">8")
        println("fed by density against usual: " + lab.indices.joinToString(", ") { b -> "%s x: %.0f%% of heads fed %.2f".format(lab[b], pct(byCrowd[b], byCrowd.sum()), byCrowdFed[b] / byCrowd[b].coerceAtLeast(1e-9)) })
    }
}

private const val HexDrinkingWaterKm = 3
