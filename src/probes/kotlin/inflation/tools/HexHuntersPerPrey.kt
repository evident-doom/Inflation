package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.ecology.get

/**
 * What the viewer's "Hunters per prey" layer is made of: after four weeks from the start (a test world's
 * saved state, or --load), the layer's value per hex worked out as the viewer did before it blanked hexes with under 1 kg/km² of big game (kg of non-human
 * hunters, residents and packs, per 100 kg of the other big animals, over the hex and its ring), and then,
 * for each kind of country and for the hexes reading 10 or more and 20 or more: the share of hexes that
 * high, hunters' kg/km² by kind and how well fed they are, big game kg/km² by kind, and the small life
 * (rodents, reptiles, hares, hyrax, tortoises, birds) the layer leaves out of the prey, kg/km².
 *
 * Usage: hex-hunters-per-prey (as hex-fauna-check: --world NAME or --load FILE, [--replicate R], ...)
 */
fun main(args: Array<String>) {
    val options = FaunaCheckOptions.parse(args)
    val sim = options.start()
    repeat(4) { sim.step() }
    val hw = sim.hw; val hex = sim.hex; val land = sim.land; val fauna = sim.fauna; val sl = fauna.small
    val n = fauna.n; val nh = hex.size
    val biomeOf = IntArray(n) { pl ->
        val c = IntArray(Biome.entries.size)
        for (cell in hw.placeCells[pl]) c[sim.world.biome[cell].ordinal]++
        c.indices.maxBy { c[it] }
    }
    // Every non-human kind, residents first and then the group kinds, by name; kg per hex, and heads x fed per hex for the hunters.
    val names = (fauna.kinds.map { it.name } + sim.groups.kinds.filter { !it.human }.map { it.name }).distinct()
    val idx = names.withIndex().associate { it.value to it.index }
    val hunter = BooleanArray(names.size)
    fauna.kinds.forEach { hunter[idx.getValue(it.name)] = it.carnivore }
    sim.groups.kinds.filter { !it.human }.forEach { hunter[idx.getValue(it.name)] = it.carnivore }
    val kg = Array(names.size) { DoubleArray(nh) }
    val heads = Array(names.size) { DoubleArray(nh) }; val fedHeads = Array(names.size) { DoubleArray(nh) }
    for ((k, s) in fauna.kinds.withIndex()) for (p in 0 until n) {
        val h = hw.placeHex[p]; val i = idx.getValue(s.name)
        val hd = (fauna.adults[k][p] + 0.3f * fauna.young[k][p]) * land.areaKm2[p]
        kg[i][h] += (hd * s.massKg).toDouble(); heads[i][h] += hd.toDouble(); fedHeads[i][h] += (hd * fauna.fed[k][p]).toDouble()
    }
    for (g in sim.groups.groups) {
        if (!g.alive || g.species.human) continue
        val h = hw.placeHex[g.place]; val i = idx.getValue(g.species.name)
        val hd = g.adults + 0.3 * g.young
        kg[i][h] += hd * g.species.massKg; heads[i][h] += hd; fedHeads[i][h] += hd * g.fed
    }
    val area = DoubleArray(nh); val small = DoubleArray(nh)
    val hexBiome = IntArray(nh) { -1 }; val biomeArea = Array(nh) { DoubleArray(0) }
    for (p in 0 until n) {
        val h = hw.placeHex[p]; val a = land.areaKm2[p].toDouble()
        area[h] += a
        small[h] += a * (sl.rodents[p] + sl.herps[p] + sl.hares[p] + sl.hyrax[p] + sl.tortoises[p] + sl.birds[p])
        if (biomeArea[h].isEmpty()) biomeArea[h] = DoubleArray(Biome.entries.size)
        biomeArea[h][biomeOf[p]] += a
    }
    for (h in 0 until nh) if (area[h] > 0) hexBiome[h] = biomeArea[h].indices.maxBy { biomeArea[h][it] }
    val hunterKg = DoubleArray(nh) { h -> names.indices.filter { hunter[it] }.sumOf { kg[it][h] } }
    val preyKg = DoubleArray(nh) { h -> names.indices.filter { !hunter[it] }.sumOf { kg[it][h] } }
    // The layer's value, as ViewLayers works it out.
    val value = DoubleArray(nh) { h ->
        if (area[h] <= 0) return@DoubleArray Double.NaN
        var hu = hunterKg[h]; var pr = preyKg[h]
        for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0) { hu += hunterKg[q]; pr += preyKg[q] } }
        if (pr <= 0.0) (if (hu > 0.0) 100.0 else 0.0) else minOf(100.0, 100.0 * hu / pr)
    }

    fun describe(label: String, hs: List<Int>, all: Int) {
        if (hs.isEmpty()) { println("$label: none"); return }
        val a = hs.sumOf { area[it] }
        val vs = hs.map { value[it] }.sorted()
        println("$label: ${hs.size} hexes (%.1f%% of land hexes), %.0f km²; layer median %.1f, 90th %.1f; at 10+ %.0f%%, at 20+ %.0f%%, at the cap of 100 %.0f%%".format(
            100.0 * hs.size / all, a, vs[vs.size / 2], vs[(vs.size * 9) / 10], 100.0 * vs.count { it >= 10 } / vs.size,
            100.0 * vs.count { it >= 20 } / vs.size, 100.0 * vs.count { it >= 100 } / vs.size))
        val hu = hs.sumOf { hunterKg[it] }; val pr = hs.sumOf { preyKg[it] }
        println("   hunters %.2f kg/km², big game %.1f kg/km² (pooled ratio %.1f kg per 100 kg), small life left out %.0f kg/km²".format(
            hu / a, pr / a, if (pr > 0) 100 * hu / pr else Double.NaN, hs.sumOf { small[it] } / a))
        fun line(hunters: Boolean) = names.indices.filter { hunter[it] == hunters }.map { i -> i to hs.sumOf { kg[i][it] } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.joinToString(", ") { (i, k) ->
                val hd = hs.sumOf { heads[i][it] }
                if (hunters) "%s %.3f (fed %.2f)".format(names[i], k / a, if (hd > 0) hs.sumOf { fedHeads[i][it] } / hd else Double.NaN)
                else "%s %.2f".format(names[i], k / a)
            }
        println("   hunters by kind, kg/km²: " + line(true))
        println("   big game by kind, kg/km²: " + line(false))
    }

    val landHexes = (0 until nh).filter { area[it] > 0 }
    println("Hunters per prey (kg of non-human hunters per 100 kg of other big animals, hex and its ring), after 4 weeks")
    describe("all land", landHexes, landHexes.size)
    describe("hexes at 10+", landHexes.filter { value[it] >= 10 }, landHexes.size)
    describe("hexes at 20+", landHexes.filter { value[it] >= 20 }, landHexes.size)
    for (b in Biome.entries.indices) {
        val hs = landHexes.filter { hexBiome[it] == b }
        if (hs.size < landHexes.size / 200) continue
        describe("by country: " + Biome.entries[b].name.lowercase().replace('_', ' '), hs, landHexes.size)
    }
}
