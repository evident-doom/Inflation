package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome

/**
 * How thick each kind stands in each kind of country, as the country is now ([inflation.world.HexCountry.kind], read
 * afresh each step), over the last year: every resident kind (the settled wolves and lions among them) and every kind in
 * groups (packs and people's bands, at the place each stands), heads (grown and young) per km² over all that country's
 * land, the share of its land where the kind stands at 0.01/km² or more, its density there, the 90th percentile of its
 * places' density at the year's end, and kg/km²; and each country's plant-eater and hunter kg/km². For checking against
 * densities measured in the field.
 *
 * Usage: kind-density (--world NAME | --load FILE | --seed N ...) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val names = (fauna.kinds.map { it.name } + sim.groups.kinds.map { it.name }).distinct()
    val idx = names.withIndex().associate { it.value to it.index }
    val mass = DoubleArray(names.size); val hunter = BooleanArray(names.size)
    for (s in fauna.kinds + sim.groups.kinds) { val i = idx.getValue(s.name); mass[i] = s.massKg.toDouble(); hunter[i] = s.carnivore || s.human }
    val nb = Biome.entries.size; val nk = names.size
    val area = DoubleArray(nb)
    val heads = Array(nk) { DoubleArray(nb) }; val occArea = Array(nk) { DoubleArray(nb) }; val occHeads = Array(nk) { DoubleArray(nb) }
    val dens = Array(nk) { DoubleArray(n) }
    val perYear = sim.stepsPerYear
    val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        for (row in dens) java.util.Arrays.fill(row, 0.0)
        for ((k, s) in fauna.kinds.withIndex()) { val i = idx.getValue(s.name)
            for (p in 0 until n) dens[i][p] += (fauna.adults[k][p] + fauna.young[k][p]).toDouble() }
        for (g in sim.groups.groups) { if (!g.alive) continue
            val i = idx.getValue(g.species.name); val p = g.place.i
            dens[i][p] += (g.adults + g.young) / land.areaKm2[p].toDouble() }
        for (p in 0 until n) {
            val b = land.country.kind[p]; val a = land.areaKm2[p].toDouble()
            area[b] += a
            for (i in 0 until nk) { val d = dens[i][p]; if (d <= 0.0) continue
                heads[i][b] += d * a
                if (d >= 0.01) { occArea[i][b] += a; occHeads[i][b] += d * a } }
        }
    }
    val land0 = area.sum()
    println("kind-density: last of $years years, by kind of country as it is now (read each step), year means")
    println("  per kind: heads/km² over the country | share of its land with 0.01/km² or more | heads/km² there | 90th pct of places at year end | kg/km²")
    for (b in Biome.entries.sortedByDescending { area[it.ordinal] }) {
        val bi = b.ordinal; if (area[bi] / land0 < 0.002) continue
        val places = (0 until n).filter { land.country.kind[it] == bi }
        var herbKg = 0.0; var huntKg = 0.0
        for (i in 0 until nk) { val kg = heads[i][bi] / area[bi] * mass[i]; if (hunter[i]) huntKg += kg else herbKg += kg }
        println("%s (%.1f%% of land): plant-eaters %.0f kg/km², hunters and people %.0f kg/km²".format(b.label, 100 * area[bi] / land0, herbKg, huntKg))
        for (i in names.indices.sortedByDescending { heads[it][bi] * mass[it] }) {
            if (heads[i][bi] <= 0.0) continue
            val d = heads[i][bi] / area[bi]; if (d < 1e-4) continue
            val sorted = places.map { dens[i][it] }.sorted()
            val p90 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 9) / 10]
            val occ = occArea[i][bi] / area[bi]
            println("  %-14s %8.3f | %3.0f%% | %8.3f | %8.3f | %7.1f".format(names[i], d, 100 * occ,
                if (occArea[i][bi] > 0) occHeads[i][bi] / occArea[i][bi] else 0.0, p90, d * mass[i]))
        }
    }
}
