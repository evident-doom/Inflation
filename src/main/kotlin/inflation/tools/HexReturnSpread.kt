package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.learning.HexPpo
import inflation.learning.Role
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
    val a = Args(args, "hex-return-spread DIR [--worlds N] [--size KM] [--years N]")
    val worlds = a.int("--worlds", 2); val size = a.int("--size", 1000); val years = a.int("--years", 2)
    val dir = File(a.rest().single())
    a.done()
    val kinds = Catalogue.learned
    val models = Role.entries.map { SharedMover.load(it.file(dir)) }
    val all = (0 until worlds).toList().parallelStream().map { w -> HexPpo.play(WorldConfig(seed = 50_000L + w, widthKm = size, heightKm = size), years, kinds, models).first }.toList().flatten()
    for (r in Role.entries) {
        val rs = all.filter { Role.of(it.kind) == r }.map { it.ret.toDouble() }.sorted()
        if (rs.isEmpty()) continue
        val m = rs.average(); val sd = kotlin.math.sqrt(rs.sumOf { (it - m) * (it - m) } / rs.size)
        fun q(f: Double) = rs[((rs.size - 1) * f).toInt()]
        println("%s: %d choices, return mean %.4f sd %.4f; 5%% %.4f, 25%% %.4f, median %.4f, 75%% %.4f, 95%% %.4f; exactly 1: %.1f%%".format(
            r, rs.size, m, sd, q(0.05), q(0.25), q(0.5), q(0.75), q(0.95), 100.0 * rs.count { kotlin.math.abs(it - 1.0) < 1e-6 } / rs.size))
    }
}
