package inflation.tools

import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.learning.SharedMover
import inflation.learning.softmax
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import inflation.ecology.HexFeatures
import inflation.ecology.Species

/**
 * How differently movement networks choose from the hand-written behaviour and from one another:
 * worlds run on the hand-written behaviour, every choice of the given role is kept (candidates'
 * features, the band's view of itself, the hand-written scores), and each network is asked what it
 * would choose there. Prints, per network: how often its best place is the hand-written one, the
 * probability it gives that place, how often it stays, and how far its choices (as probabilities)
 * are from the first network's.
 *
 * Usage: hex-policy-diff [--role people] [--worlds N] [--size KM] [--weeks N] NET.mlp [NET.mlp ...]
 * (a network's .residual file is used if it lies beside it; name a file NET.mlp@0 to leave it out)
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-policy-diff [--role people] [--worlds N] [--size KM] [--weeks N] NET.mlp [NET.mlp ...]")
    val role = a.string("--role") ?: "people"; val worlds = a.int("--worlds", 4); val size = a.int("--size", 1000); val weeks = a.int("--weeks", 26)
    val names = a.rest()
    a.done()
    val nets = names.map { name -> val (path, res) = name.split("@").let { it[0] to it.getOrNull(1)?.toFloat() }
        name to SharedMover.load(File(path)).also { m -> if (res != null) m.residual = res } }
    val kinds = Catalogue.learned
    fun mine(s: Species) = when (role) { "people" -> s.human; "hunters" -> s.pack; else -> !s.carnivore }
    class Seen(val kind: Species, val f: Array<FloatArray>, val band: FloatArray?, val hand: FloatArray, val temper: FloatArray)
    val seen = (0 until worlds).toList().parallelStream().map { w ->
        val run = freshSim(WorldConfig(seed = 7000L + w, widthKm = size, heightKm = size), kinds)
        val kept = ConcurrentLinkedQueue<Seen>()
        for (s in kinds) if (mine(s)) run.groups.policies[s] = HexPolicy { g, f, b, _ ->
            kept += Seen(g.species, Array(f.size) { f[it].copyOf() }, g.band?.state?.copyOf(), b.copyOf(), g.temper.copyOf())
            var best = 0; for (i in b.indices) if (b[i] > b[best]) best = i; best
        }
        repeat(weeks) { run.step() }
        kept.toList()
    }.toList().flatten().filter { it.f.size > 1 }
    println("%d %s choices with more than one place, %.1f places each on average".format(seen.size, role, seen.map { it.f.size }.average()))
    var first: List<FloatArray>? = null
    for ((name, m) in nets) {
        val b = m.net.buffers()
        var agree = 0; var pHand = 0.0; var stay = 0; var handStay = 0; var tv = 0.0
        val ps = ArrayList<FloatArray>()
        for (c in seen) {
            val sc = FloatArray(c.f.size) { i ->
                m.fill(c.kind, c.f[i], c.band, b.a[0], c.temper)
                m.net.forward(b) + m.residual * c.hand[i] + m.pull(c.temper, c.f[i])
            }
            val p = softmax(sc); ps += p
            var best = 0; for (i in sc.indices) if (sc[i] > sc[best]) best = i
            var hb = 0; for (i in c.hand.indices) if (c.hand[i] > c.hand[hb]) hb = i
            if (best == hb) agree++
            pHand += p[hb]
            val st = c.f.indices.firstOrNull { c.f[it].getOrElse(HexFeatures.STAYS) { 0f } > 0.5f } ?: -1
            if (best == st) stay++; if (hb == st) handStay++
        }
        first?.let { f0 -> for (i in ps.indices) { var d = 0.0; for (j in ps[i].indices) d += abs(ps[i][j] - f0[i][j]); tv += d / 2 } }
        if (first == null) first = ps
        println("%s (residual %.1f): best = hand-written's %.1f%%, probability on it %.3f, stays %.1f%% (hand-written %.1f%%)%s".format(
            name, m.residual, 100.0 * agree / seen.size, pHand / seen.size, 100.0 * stay / seen.size, 100.0 * handStay / seen.size,
            if (ps === first) "" else ", apart from the first by %.3f".format(tv / seen.size)))
    }
}
