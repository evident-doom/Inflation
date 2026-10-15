package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexPpo
import inflation.learning.SharedMover
import inflation.world.WorldConfig
import java.io.File

/**
 * What the training's returns look like: worlds played as in a round of hex-ppo with the networks in
 * DIR, and for each role the recorded choices' returns: mean, spread, quantiles, and the share that
 * are exactly 1 (the group neither grew nor shrank over its horizon).
 *
 * Usage: hex-return-spread DIR [--worlds N] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    val dir = File(args[0]); var worlds = 2; var size = 1000; var years = 2
    var k = 1
    while (k < args.size) { when (args[k]) { "--worlds" -> worlds = args[++k].toInt(); "--size" -> size = args[++k].toInt(); "--years" -> years = args[++k].toInt() }; k++ }
    val kinds = Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }
    val models = HexPpo.ROLES.map { SharedMover.load(File(dir, "$it.mlp")) }
    val all = (0 until worlds).toList().parallelStream().map { w -> HexPpo.play(WorldConfig(seed = 50_000L + w, widthKm = size, heightKm = size), years, kinds, models).first }.toList().flatten()
    for ((ri, r) in HexPpo.ROLES.withIndex()) {
        val rs = all.filter { HexPpo.role(it.kind) == ri }.map { it.ret.toDouble() }.sorted()
        if (rs.isEmpty()) continue
        val m = rs.average(); val sd = kotlin.math.sqrt(rs.sumOf { (it - m) * (it - m) } / rs.size)
        fun q(f: Double) = rs[((rs.size - 1) * f).toInt()]
        println("%s: %d choices, return mean %.4f sd %.4f; 5%% %.4f, 25%% %.4f, median %.4f, 75%% %.4f, 95%% %.4f; exactly 1: %.1f%%".format(
            r, rs.size, m, sd, q(0.05), q(0.25), q(0.5), q(0.75), q(0.95), 100.0 * rs.count { kotlin.math.abs(it - 1.0) < 1e-6 } / rs.size))
    }
}
