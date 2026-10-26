package inflation.tools

import inflation.world.toHexArray
import inflation.world.HexArray
import inflation.world.get
import inflation.world.set

import inflation.world.Hex
import inflation.world.Place
import inflation.Args
import inflation.Tuning
import inflation.ecology.HexSim
import inflation.ecology.HexSmallLife
import inflation.ecology.HexSnapshot
import inflation.ecology.coldNeed
import inflation.ecology.HexFood
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.ui.ViewLayers
import inflation.world.Date
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * Where the viewer's "Hungry next to plenty" layer is high, and why: the layer (grazers and browsers
 * going short per km², times the most grass and browse their kind could eat within two hexes) worked
 * out each week exactly as the viewer does, and split by the kind of hex: small islands,
 * coasts, peaks (higher than every land neighbour), high ground (1500 m and up) and the rest.
 *
 * For each class: its share of the map's top 5% of the layer, the means of hunger and plenty, how
 * many plant-eaters stand there, and the hunger by what keeps them off the food (crags, distance
 * from water, biting insects, snow, poor forage, cold) and by kind.
 *
 * Usage: hex-plenty-check (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-plenty-check (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", Date.DAYS_PER_YEAR / cfg.daysPerStep)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val hex = sim.hex; val hw = sim.hw; val land = sim.land; val fauna = sim.fauna; val nh = hex.size; val n = land.n
    val layers = ViewLayers(sim)
    val tuning = Tuning.config

    val hc = HexClasses(sim); val classes = HexClasses.NAMES; val cls = hc.cls; val nc = classes.size
    hc.print()

    // Accumulated over the weeks, by class.
    val hexWeeks = DoubleArray(nc); val inTop = DoubleArray(nc); val layerSum = DoubleArray(nc)
    val hungerSum = DoubleArray(nc); val plentySum = DoubleArray(nc); val withHeads = DoubleArray(nc)
    val headsKm2 = DoubleArray(nc); val selfMax = DoubleArray(nc)
    // In the top hexes: heads there, hunger, plenty, plant-eater kg/km² at the plenty hex and at the hex itself.
    val topHeads = DoubleArray(nc); val topHunger = DoubleArray(nc); val topPlenty = DoubleArray(nc)
    val topEaterHere = DoubleArray(nc); val topEaterThere = DoubleArray(nc); val topFew = DoubleArray(nc)
    // Hunger mass (heads x (1 - fed)) and, weighted by it, what keeps them off the food.
    val hungerMass = DoubleArray(nc); val headMass = DoubleArray(nc)
    val fCrag = DoubleArray(nc); val fTether = DoubleArray(nc); val fHarass = DoubleArray(nc); val fSnow = DoubleArray(nc)
    val fQuality = DoubleArray(nc); val fCold = DoubleArray(nc); val fBandNb = DoubleArray(nc); val fLeftHere = DoubleArray(nc)
    val byKind = Array(nc) { HashMap<String, Double>() }
    // The same layer's top 5% if it were weighted by how many go hungry (hunger mass per km² x plenty), or
    // took its plenty only from hexes reached over land; and each class's own forage left and plant-eaters.
    val inTopOverLand = DoubleArray(nc); val ownLeft = DoubleArray(nc); val eaterSum = DoubleArray(nc)

    fun weigh(c: Int, p: Int, s: inflation.ecology.Species, hm: Double) {
        val w = sim.weather
        fCrag[c] += hm * HexFood.access(s, land.crag[p]); fTether[c] += hm * fauna.tether(s, Place(p))
        fHarass[c] += hm * (1f - HexSmallLife.HARASS_FEED * fauna.small.harassment(inflation.world.Place(p)))
        if (w.snowpack[p] > s.snowLimitMm) fSnow[c] += hm
        fQuality[c] += hm * land.plants.nourishment(inflation.world.Place(p))
        fCold[c] += hm * coldNeed(s, fauna.feltC(s, Place(p), w.temperature[p]), w.date.month, tuning)
        val h = hw.placeHex[p]; fBandNb[c] += hm * (0 until 6).count { d -> val q = hex.neighbour(h, d); !q.none && !hw.placeNear(Place(p), q).none }
        fLeftHere[c] += hm * (fauna.grazeLeft[p] + fauna.browseLeft[p])
    }

    val eaterKg = DoubleArray(nh); val area = DoubleArray(nh)
    for (p in 0 until n) area[hw.placeHex[p]] += land.areaKm2[p].toDouble()
    repeat(weeks) {
        sim.step()
        layers.week()
        val v = layers.now[ViewLayers.Measure.HUNGRY_BY_PLENTY.name]!!
        val hunger = layers.hunger; val plenty = layers.plenty
        val left = FloatArray(nh); run {
            val s = DoubleArray(nh)
            for (p in 0 until n) s[hw.placeHex[p]] += (fauna.grazeLeft[p] + fauna.browseLeft[p]) * land.areaKm2[p].toDouble()
            for (h in 0 until nh) left[h] = if (area[h] > 0) (s[h] / area[h]).toFloat() else Float.NaN
        }
        // Plant-eaters by hex, kg/km².
        eaterKg.fill(0.0); val heads = DoubleArray(nh)
        for ((k, s) in fauna.kinds.withIndex()) {
            if (s.carnivore) continue
            for (p in 0 until n) { val hd = (fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]; eaterKg[hw.placeHex[p]] += hd * s.massKg; heads[hw.placeHex[p]] += hd.toDouble() }
        }
        val vals = (0 until nh).filter { cls[it] >= 0 && !v[it].isNaN() }.map { v[it] }.sorted()
        val top = if (vals.isEmpty()) Float.MAX_VALUE else vals[(0.95 * (vals.size - 1)).toInt()]
        val overLand = FloatArray(nh) { h ->
            if (cls[h] < 0 || v[h].isNaN()) return@FloatArray Float.NaN
            var best = 0f
            for (q in landRing2(sim, Hex(h))) { val l = left[q]; if (!l.isNaN() && l > best) best = l }
            hunger[h] * best
        }
        fun topOf(x: FloatArray): Float { val s = x.filter { !it.isNaN() }.sorted(); return if (s.isEmpty()) Float.MAX_VALUE else s[(0.95 * (s.size - 1)).toInt()] }
        val topLand = topOf(overLand)
        for (h in 0 until nh) {
            val c = cls[h]; if (c < 0 || v[h].isNaN()) continue
            if (overLand[h] >= topLand && overLand[h] > 0f) inTopOverLand[c]++
            if (!left[h].isNaN()) ownLeft[c] += left[h]
            eaterSum[c] += eaterKg[h] / area[h]
            hexWeeks[c]++; layerSum[c] += v[h]; hungerSum[c] += hunger[h]; plentySum[c] += plenty[h]
            if (heads[h] > 0) withHeads[c]++
            headsKm2[c] += heads[h] / area[h]
            if (!left[h].isNaN() && left[h] >= plenty[h]) selfMax[c]++
            if (v[h] >= top && v[h] > 0f) {
                inTop[c]++; topHeads[c] += heads[h]; topHunger[c] += hunger[h]; topPlenty[c] += plenty[h]
                if (heads[h] < FEW) topFew[c]++
                topEaterHere[c] += eaterKg[h] / area[h]
                // (The hex that gives the plenty.)
                var best = Hex(h); var bv = -1f
                for (q in ring2(sim, Hex(h))) { val l = left[q]; if (!l.isNaN() && l > bv) { bv = l; best = q } }
                topEaterThere[c] += eaterKg[best] / area[best]
            }
        }
        // Residents' hunger, and what keeps them off the food.
        for ((k, s) in fauna.kinds.withIndex()) {
            if (s.carnivore) continue
            for (p in 0 until n) {
                val hd = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                if (hd <= 0.0) continue
                val c = cls[hw.placeHex[p]]; if (c < 0) continue
                val hm = hd * (1.0 - fauna.fed[k][p])
                headMass[c] += hd; hungerMass[c] += hm
                byKind[c].merge(s.name, hm, Double::plus)
                weigh(c, p, s, hm)
            }
        }
    }

    fun pct(x: Double, y: Double) = if (y > 0) 100.0 * x / y else Double.NaN
    val allTop = inTop.sum(); val allHex = hexWeeks.sum()
    println("\nover $weeks weeks, the layer's top 5% of land hexes each week:")
    println("%-13s %6s %6s %8s %7s %8s %8s %8s".format("class", "hexes", "top", "layer", "short/km2", "plenty", "heads/km2", "w/heads"))
    for (c in 0 until nc) println("%-13s %5.1f%% %5.1f%% %8.4f %7.3f %8.4f %8.2f %7.0f%%".format(classes[c],
        pct(hexWeeks[c], allHex), pct(inTop[c], allTop), layerSum[c] / hexWeeks[c], hungerSum[c] / hexWeeks[c], plentySum[c] / hexWeeks[c],
        headsKm2[c] / hexWeeks[c], pct(withHeads[c], hexWeeks[c])))
    println("\nthe top 5% if plenty were any grass and browse left (whoever could eat it); own forage left, g/m², and plant-eaters, kg/km²")
    for (c in 0 until nc) if (hexWeeks[c] > 0) println("%-13s any forage %5.1f%%  own left %6.1f  eaters %7.1f".format(classes[c],
        pct(inTopOverLand[c], inTopOverLand.sum()), 1000 * ownLeft[c] / hexWeeks[c], eaterSum[c] / hexWeeks[c]))
    println("\nin the top hexes: plant-eaters standing there, hunger, plenty; plant-eater kg/km² there and at the hex giving the plenty;")
    println("share with under $FEW plant-eaters in the hex; and share of all hexes whose own forage left is the plenty")
    for (c in 0 until nc) if (inTop[c] > 0) println("%-13s heads %8.1f  short/km2 %.2f  plenty %.4f  eaters here %7.1f  at plenty %7.1f  few %3.0f%%  self-max (all) %3.0f%%".format(classes[c],
        topHeads[c] / inTop[c], topHunger[c] / inTop[c], topPlenty[c] / inTop[c], topEaterHere[c] / inTop[c], topEaterThere[c] / inTop[c],
        pct(topFew[c], inTop[c]), pct(selfMax[c], hexWeeks[c])))
    println("\nplant-eaters' hunger (heads x (1 - fed)) by class, and weighted by it, what stands between them and the food:")
    println("  access: share of the food not behind crags; tether: share within reach of water; harass: share of feeding time left by biting insects;")
    println("  snowed: share under snow deeper than the kind digs; quality: what forage nourishes (dry 0.x .. green 1); cold: need x;")
    println("  band nb: neighbours with ground at the same height (0-6); left: grass and browse still left where they stand, g/m²")
    for (c in 0 until nc) if (hungerMass[c] > 0) {
        val m = hungerMass[c]
        println("%-13s hunger %4.1f%% of heads  access %.2f tether %.2f harass %.2f snowed %.2f quality %.2f cold %.2f band nb %.1f left %.1f".format(
            classes[c], pct(m, headMass[c]), fCrag[c] / m, fTether[c] / m, fHarass[c] / m, fSnow[c] / m, fQuality[c] / m, fCold[c] / m,
            fBandNb[c] / m, 1000 * fLeftHere[c] / m))
        println("%13s most hunger: ".format("") + byKind[c].entries.sortedByDescending { it.value }.take(6)
            .joinToString(", ") { "%s %.0f%%".format(it.key, pct(it.value, m)) })
    }
}

/**
 * Each land hex's class for the plenty and anomaly checks: on a small island (a land mass of at most
 * [SMALL_ISLAND] hexes), on the coast, a peak (higher than every land neighbour, [PEAK_M] and up),
 * high ground ([HIGH_M] and up), or the rest; -1 for no land.
 */
internal class HexClasses(sim: HexSim) {
    private val hex = sim.hex; private val hw = sim.hw; private val nh = hex.size
    private val mass = IntArray(nh) { -1 }; private val massSize = ArrayList<Int>()
    init {
        for (h0 in hex.all) {
            if (hw.land[h0] <= 0f || mass[h0] >= 0) continue
            val id = massSize.size; var size = 0
            val stack = ArrayDeque<Hex>().apply { add(h0) }; mass[h0] = id
            while (stack.isNotEmpty()) {
                val h = stack.removeLast(); size++
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && hw.land[q] > 0f && mass[q] < 0) { mass[q] = id; stack.add(q) } }
            }
            massSize += size
        }
    }
    val cls = IntArray(nh) { h ->
        if (hw.land[h] <= 0f) -1
        else if (massSize[mass[h]] <= SMALL_ISLAND) 0
        else if (hw.coast[h]) 1
        else if ((0 until 6).all { d -> val q = hex.neighbour(Hex(h), d); q.none || hw.land[q] <= 0f || hw.elevationM[q] < hw.elevationM[h] } && hw.elevationM[h] >= PEAK_M) 2
        else if (hw.elevationM[h] >= HIGH_M) 3
        else 4
    }
    fun print() {
        println("land masses: ${massSize.size}, of them small islands (<= $SMALL_ISLAND hexes): ${massSize.count { it <= SMALL_ISLAND }}" +
            " (sizes ${massSize.filter { it <= SMALL_ISLAND }.sorted().joinToString(",")})")
        println("hexes by class: " + NAMES.indices.joinToString(", ") { c -> "${NAMES[c]} ${cls.count { it == c }}" })
    }
    companion object {
        val NAMES = listOf("small island", "coast", "peak", "high ground", "the rest")
        const val SMALL_ISLAND = 100
        const val PEAK_M = 800f
        const val HIGH_M = 1500f
    }
}

/** Plant-eaters in a hex under which its hunger is a handful's. */
private const val FEW = 20.0

/** The land hexes within two steps of [h] over land (not across a strait). */
private val landRings = HashMap<Hex, HexArray>()
private fun landRing2(sim: HexSim, h: Hex): HexArray = landRings.getOrPut(h) {
    val out = linkedSetOf(h)
    for (d in 0 until 6) {
        val q = sim.hex.neighbour(h, d); if (q.none || sim.hw.land[q] <= 0f) continue
        out += q
        for (e in 0 until 6) { val r = sim.hex.neighbour(q, e); if (!r.none && sim.hw.land[r] > 0f) out += r }
    }
    out.toHexArray()
}

private val rings = HashMap<Hex, HexArray>()
private fun ring2(sim: HexSim, h: Hex): HexArray = rings.getOrPut(h) {
    val out = linkedSetOf(h)
    for (d in 0 until 6) { val q = sim.hex.neighbour(h, d); if (!q.none) { out += q; for (e in 0 until 6) { val r = sim.hex.neighbour(q, e); if (!r.none) out += r } } }
    out.toHexArray()
}
