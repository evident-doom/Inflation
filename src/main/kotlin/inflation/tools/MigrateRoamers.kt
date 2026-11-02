package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.settleIn
import inflation.run.TestWorlds
import inflation.world.WorldCache
import java.io.File

/**
 * One-shot move of saved worlds onto the settled residents and roaming packs of wolves and lions (docs/design/roaming-packs.md):
 * a world saved before they were residents too ([HexSnapshot.load]'s beforeRoamers) loads with their settled at none, and
 * every pack of theirs that holds ground settles in on it ([settleIn]), heads, fat and all; those without ground roam on.
 * The old state is kept beside the new one as state-before-roamers.bin. Remove this tool, and the load's allowance, once the
 * test and training worlds are moved.
 *
 * Usage: migrate-roamers --worlds m5-1,m5-2,m5-3
 */
fun main(args: Array<String>) {
    val a = Args(args, "migrate-roamers --worlds a,b,...")
    val names = (a.string("--worlds") ?: error("--worlds a,b,...")).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    a.done()
    for (name in names) {
        val e = TestWorlds.named(name)
        val sim = HexSim(WorldCache.load(e.config) { println(it) })
        HexSnapshot.load(sim, e.state, beforeRoamers = true)
        val fauna = sim.fauna; val groups = sim.groups
        for (s in Catalogue.roamingResidents) {
            val k = fauna.slot(s)
            // (What the fresh model gave their settled is not the saved world's: they start at none.)
            fauna.adults[k.i].fill(0f); fauna.young[k.i].fill(0f); fauna.leaving[k.i]?.fill(0f)
        }
        val before = Catalogue.roamingResidents.associateWith { s -> groups.groups.filter { it.alive && it.species == s }.sumOf { it.size }.toDouble() }
        for (g in groups.groups) if (g.alive && g.band == null && g.species in Catalogue.roamingResidents && !g.home.none) groups.settleIn(g)
        groups.groups.removeIf { !it.alive }
        for (s in Catalogue.roamingResidents) {
            val settled = fauna.count(fauna.slot(s)); val roaming = groups.groups.filter { it.alive && it.species == s }.sumOf { it.size }.toDouble()
            val was = before.getValue(s)
            println("$name: ${s.name} %.0f in packs before; settled %.0f, roaming %.0f after".format(was, settled, roaming))
            require(kotlin.math.abs(settled + roaming - was) < 0.5 + 1e-4 * was) { "$name: ${s.name}'s heads did not add up" }
        }
        val old = File(e.dir, "state-before-roamers.bin")
        if (!old.exists()) e.state.copyTo(old)
        HexSnapshot.save(sim, e.state)
        println("$name: saved; the old state is in $old")
    }
}
