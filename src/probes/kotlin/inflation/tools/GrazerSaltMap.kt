package inflation.tools

import inflation.Args
import inflation.ecology.GRAZER_SODIUM_NEED
import inflation.ecology.HexSim
import inflation.ecology.LICK
import inflation.world.Place
import inflation.world.World
import inflation.world.WorldConfig
import inflation.world.get

/**
 * Where plant-eaters are short of sodium on a world ([inflation.ecology.forageSodium]): the share of places by a salt lick
 * or on the coast, and of the rest by how far their forage meets a pure grazer's need. Fresh land, no years run.
 *
 * Usage: grazer-salt-map [--seed N] [--width KM] [--height KM]
 */
fun main(args: Array<String>) {
    val a = Args(args, "grazer-salt-map [--seed N] [--width KM] [--height KM]")
    val base = WorldConfig(widthKm = 1000, heightKm = 2000)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    a.done()
    val sim = HexSim(World.generate(cfg))
    val na = sim.fauna.forageSodiumAt
    val n = sim.land.n
    var lick = 0; val bins = IntArray(5); var least = Float.POSITIVE_INFINITY
    for (i in 0 until n) {
        val x = na[Place(i)]
        if (x >= LICK) { lick++; continue }
        least = minOf(least, x)
        val met = x / GRAZER_SODIUM_NEED
        bins[when { met >= 1f -> 0; met >= 0.9f -> 1; met >= 0.7f -> 2; met >= 0.5f -> 3; else -> 4 }]++
    }
    fun pc(k: Int) = "%.1f%%".format(100.0 * k / n)
    println("seed ${cfg.seed}, ${cfg.widthKm} x ${cfg.heightKm} km, $n places: lick or coast ${pc(lick)}; forage meets the need: in full ${pc(bins[0])}, " +
        "0.9-1 ${pc(bins[1])}, 0.7-0.9 ${pc(bins[2])}, 0.5-0.7 ${pc(bins[3])}, under 0.5 ${pc(bins[4])}; least forage sodium %.3f%% of dry matter".format(100f * least))
}
