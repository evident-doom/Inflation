package inflation.tools

import inflation.Tuning
import inflation.ecology.HexFauna
import inflation.ecology.preyBiomass
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place

/**
 * Where each hunter (and each plant-eater that holds ground) stands against the room its territories give
 * ([inflation.ecology.HexFauna.usualAt]), over the last year, sampled every step: its heads; the room over the places it
 * holds and over every place with game of its size ([inflation.ecology.preyBiomass] > 0); the share of its heads where
 * they stand past [Tuning.hunterBreedSpacing] times the room (no ranges left to breed in) and past
 * [HexFauna.STRIFE_CROWD] (fighting); and, heads-weighted, the game of its size and all plant-eaters' live weight where it
 * stands, kg/km². Says whether the hunters are held by the room their game gives, by not reaching the places with room,
 * or by their breeding.
 *
 * Usage: hunter-room (--world NAME | --load FILE | --seed N ...) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val kinds = fauna.kinds
    val hunters = kinds.indices.filter { kinds[it].carnivore || kinds[it].holdsGround }
    val breedAt = Tuning.config.hunterBreedSpacing
    // [0 heads, 1 room where held, 2 room where game, 3 heads past breeding, 4 heads past strife, 5 heads x game, 6 heads x all plant-eaters kg, 7 km² held, 8 km² with game]
    val acc = Array(kinds.size) { DoubleArray(9) }
    val perYear = sim.stepsPerYear; val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        for (p in 0 until n) {
            val place = Place(p); val a = land.areaKm2[p].toDouble()
            var eaters = 0.0
            for ((j, s) in kinds.withIndex()) if (!s.carnivore && !s.holdsGround && !s.human) eaters += (fauna.adults[j][p] + fauna.young[j][p]) * s.massKg.toDouble()
            for (k in hunters) {
                val s = kinds[k]
                val game = fauna.preyBiomass(s, place).toDouble()
                val u = fauna.usualAt(k, place).toDouble()
                val d = (fauna.adults[k][p] + fauna.young[k][p]).toDouble()
                val v = acc[k]
                if (game > 0.0) { v[2] += u * a; v[8] += a }
                if (d <= 0.0) continue
                val h = d * a
                v[0] += h; v[1] += u * a; v[7] += a
                if (d > breedAt * u) v[3] += h
                if (d > HexFauna.STRIFE_CROWD * u) v[4] += h
                v[5] += h * game; v[6] += h * eaters
            }
        }
    }
    println("hunter-room: last of $years years, means over its steps. Per hunter: heads | room (usualAt x km²) where it stands | room over all places with game of its size | share of land with game it holds")
    println("  | share of heads past ${"%.2g".format(breedAt)}x room (cannot breed) | past ${"%.2g".format(HexFauna.STRIFE_CROWD)}x (fighting) | game of its size kg/km² where it stands | all plant-eaters kg/km² where it stands")
    for (k in hunters) {
        val v = acc[k]; val h = v[0].coerceAtLeast(1e-9)
        println("%-14s %9.0f | %9.0f | %9.0f | %4.0f%% | %4.0f%% | %4.0f%% | %8.0f | %8.0f".format(kinds[k].name, v[0] / perYear, v[1] / perYear, v[2] / perYear,
            100 * v[7] / v[8].coerceAtLeast(1e-9), 100 * v[3] / h, 100 * v[4] / h, v[5] / h, v[6] / h))
    }
}
