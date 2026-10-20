package inflation.learning

import inflation.ecology.HexHistory
import inflation.Config
import inflation.ecology.Catalogue
import inflation.run.Nominal
import inflation.run.TestWorlds
import inflation.run.configWith
import inflation.run.startFaunaCheck
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The training's check of a round's networks: is the ecosystem as a whole no worse with them, and does
 * each role do better with its new network than with its kept one, the other roles as they now are?
 *
 * The test world ([Config] key `ppo.checkWorld`, default lake-1) runs from its settled state for
 * `ppo.checkYears` years (default 5), its weather drawn `ppo.checkReplicates` ways (default 2), each
 * way with: every role's new network ("new"); every role's kept one ("kept"); and, for each role, its
 * kept network with the others' new ("kept hunters" and so on). The runs are their own processes, beside
 * the training, each under the training's own config with only the networks changed. A role's new network is kept when, set against its kept one with the others' new (all
 * new against "kept hunters" and so on, so that only that role's network differs):
 * - the ecosystem holds: no kind's numbers in the last whole year fall more than [FALL] below in every
 *   weather alike (a fall in one weather only is chance), no kind present is gone in every weather, and
 *   there are not fewer people in every weather; and
 * - its own members fare better: the role's kinds number more in the last whole year.
 */
class EcoCheck(private val out: File) {
    private val world = TestWorlds.named(Config.string(Config.Key.PPO_CHECK_WORLD) ?: "lake-1")
    private val years = Config.int(Config.Key.PPO_CHECK_YEARS, 5)
    private val replicates = Config.int(Config.Key.PPO_CHECK_REPLICATES, 2)
    // (Daemon threads: a run that ends is not kept alive by its check's pool.)
    private val pool = Executors.newFixedThreadPool(Config.int(Config.Key.PPO_CHECK_PARALLEL, 10)) { r -> Thread(r, "eco-check").also { it.isDaemon = true } }
    /** The networks each role plays with outside training, and that the new ones are judged against. */
    val kept = File(out, "kept")

    class Verdict(val round: Int, val roles: Map<Role, Boolean>, val lines: List<String>)

    /** The kept networks start as the ones the run starts from. */
    fun init() { if (!File(kept, Role.HUNTERS.file(kept).name).exists()) copyRoles(out, kept, Role.entries) }

    /** Checks the networks in [candidate] (a copy of round [round]'s, counted from 0) against the kept ones, beside the training. */
    fun start(round: Int, candidate: File): CompletableFuture<Verdict> = CompletableFuture.supplyAsync {
        val dir = File(out, "checks/round-${round + 1}").also { it.mkdirs() }
        val arms = linkedMapOf("new" to Role.entries.associateWith { candidate }, "kept" to Role.entries.associateWith { kept })
        for (r in Role.entries) arms["kept-$r"] = Role.entries.associateWith { if (it == r) kept else candidate }
        val runs = arms.mapValues { (name, from) ->
            val armDir = File(dir, name).also { it.mkdirs() }
            for ((r, src) in from) copyRoles(src, armDir, listOf(r))
            // The training's own config (its model keys judge the networks as they were trained), with this arm's networks.
            val cfg = File(armDir, "check.properties")
            val keys = configWith(mapOf(Config.Key.NETS to armDir.absoluteFile.invariantSeparatorsPath, WaysKind.BANDS.configKey to File(armDir, WaysKind.BANDS.file).absoluteFile.invariantSeparatorsPath))
            cfg.writer().use { keys.store(it, "check arm $name, written by the training's ecosystem check") }
            (0 until replicates).map { r -> pool.submit<File> { runOne(cfg, armDir, r) } }
        }.mapValues { (_, fs) -> fs.map { TestWorlds.Run(it.get()) } }
        judge(round, runs)
    }

    private fun runOne(cfg: File, armDir: File, r: Int): File {
        val history = File(armDir, "r$r.csv")
        val p = startFaunaCheck(cfg, "-Xmx${Config.int(Config.Key.PPO_CHECK_HEAP_GB, 12)}g",
            listOf("--world", world.name, "--years", "$years", "--replicate", "$r", "--history", history.path), File(armDir, "r$r.txt"))
        require(p.waitFor() == 0) { "check run failed: see ${File(armDir, "r$r.txt")}" }
        return history
    }

    private fun lastYear(rs: List<TestWorlds.Run>, key: String) = rs.map { it.yearly(key, it.years.last()) }.filter { !it.isNaN() }.average()

    /** Each weather's last whole year of [key]. */
    private fun lastYears(rs: List<TestWorlds.Run>, key: String) = rs.map { it.yearly(key, it.years.last()).let { v -> if (v.isNaN()) 0.0 else v } }

    /**
     * Why the ecosystem fails with [new] against [old] (the same weathers in the same order); empty when it holds.
     * A kind counts as fallen or gone only when it is so in every weather, and only one at least [RARE] strong with
     * the old networks: a few dozen animals rise and fall too much by chance to judge by.
     */
    private fun fails(new: List<TestWorlds.Run>, old: List<TestWorlds.Run>, kinds: List<String>): List<String> {
        val why = ArrayList<String>()
        for (k in kinds) {
            val n = lastYears(new, HexHistory.Key.animals(k)); val o = lastYears(old, HexHistory.Key.animals(k))
            val pairs = n.indices.filter { o[it] > 0.0 }
            if (pairs.isEmpty() || o.sum() / pairs.size < RARE) continue
            if (pairs.all { n[it] <= 0.0 }) why += "$k gone"
            else if (pairs.all { n[it] < (1 - FALL) * o[it] }) why += "$k %+.0f%%".format(100 * (n.sum() / o.sum() - 1))
        }
        val pn = lastYears(new, HexHistory.Key.animals(Catalogue.HUMAN.name)); val po = lastYears(old, HexHistory.Key.animals(Catalogue.HUMAN.name))
        if (pn.indices.all { pn[it] < po[it] }) why += "fewer people"
        return why
    }

    private fun judge(round: Int, runs: Map<String, List<TestWorlds.Run>>): Verdict {
        val new = runs.getValue("new"); val kept = runs.getValue("kept")
        val kinds = new.first().allKeys.mapNotNull { HexHistory.Key.animalKind(it) }
        val lines = ArrayList<String>()
        val cells = kinds.mapNotNull { k -> val n = lastYear(new, HexHistory.Key.animals(k)); val o = lastYear(kept, HexHistory.Key.animals(k)); if (o > 0.0) "%s %+.1f%%".format(k, 100 * (n / o - 1)) else null }
        val all = fails(new, kept, kinds)
        lines += "check round %d ecosystem (%s, %d years, %d weathers; last year, every role new against every role kept): %s -> %s".format(
            round + 1, world.name, years, replicates, cells.joinToString(", "), if (all.isEmpty()) "holds" else "fails: " + all.joinToString(", "))
        // (Reported only: whether the runs stayed within nominal limits does not decide what is kept.)
        lines += "check round %d within nominal limits (series): every role new %s; every role kept %s".format(round + 1,
            new.joinToString(", ") { it.nominal.brief() }, kept.joinToString(", ") { it.nominal.brief() })
        val roles = Role.entries.associateWith { r ->
            val alone = runs.getValue("kept-$r")
            val why = fails(new, alone, kinds)
            val own = Catalogue.learned.filter { Role.of(it) == r }.map { it.name }
            val withNew = own.sumOf { lastYears(new, HexHistory.Key.animals(it)).average() }
            val withKept = own.sumOf { lastYears(alone, HexHistory.Key.animals(it)).average() }
            val better = withNew > withKept
            lines += "check round %d %s: own kinds %.0f with the new network against %.0f with the kept one (%+.1f%%), the others new; ecosystem %s -> %s".format(
                round + 1, r, withNew, withKept, if (withKept > 0) 100 * (withNew / withKept - 1) else 0.0,
                if (why.isEmpty()) "holds" else "fails: " + why.joinToString(", "), if (better && why.isEmpty()) "kept" else "not kept")
            better && why.isEmpty()
        }
        return Verdict(round, roles, lines)
    }

    /** Applies [v]: copies each role's new network that passed from [candidate] into the kept ones. */
    fun apply(v: Verdict, candidate: File) { copyRoles(candidate, kept, v.roles.filterValues { it }.keys) }

    companion object {
        /** How far a kind may fall below its numbers with the kept networks. */
        const val FALL = 0.10
        /** Kinds fewer than this on average with the old networks are reported but do not fail the ecosystem (Olli's choice: rare kinds need tuning of their own). */
        const val RARE = 200.0

        /** Copies [roles]' networks, with their side files (and for people, the bands' ways), from [from] to [to]. */
        fun copyRoles(from: File, to: File, roles: Collection<Role>) {
            to.mkdirs()
            for (r in roles) {
                val prefixes = listOf(r.label + ".mlp") + if (r == Role.PEOPLE) listOf(WaysKind.BANDS.file) else emptyList()
                from.listFiles()?.filter { f -> f.isFile && prefixes.any { f.name.startsWith(it) } }?.forEach { it.copyTo(File(to, it.name), overwrite = true) }
            }
        }
    }
}
