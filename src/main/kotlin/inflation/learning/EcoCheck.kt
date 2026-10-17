package inflation.learning

import inflation.Config
import inflation.ecology.Catalogue
import inflation.tools.TestWorlds
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The training's check of a round's networks: is the ecosystem as a whole no worse with them, and does
 * each role do better with its new network than with its kept one, the other roles as they now are?
 *
 * The current test world ([Config] key `ppo.checkWorld`, default lake-1) runs from its settled state for
 * `ppo.checkYears` years (default 5), its weather drawn `ppo.checkReplicates` ways (default 2), each
 * way with: every role's new network ("new"); every role's kept one ("kept"); and, for each role, its
 * kept network with the others' new ("kept herds" and so on). The runs are their own processes, beside
 * the training. A role's new network is kept when
 * - the ecosystem holds: with every new network against every kept one, no kind's numbers in the last
 *   whole year fall more than [FALL] below, no kind present with the kept ones is gone, the migratory
 *   herds stand no less far from where they stood half a year before (within [SHORTER]), and there are
 *   no fewer people; and
 * - its own members fare better: the role's kinds number more in the last whole year with its new
 *   network than with its kept one, the others as they now are.
 */
class EcoCheck(private val out: File) {
    private val world = TestWorlds.named(Config.string("ppo.checkWorld") ?: "lake-1")
    private val years = Config.int("ppo.checkYears", 5)
    private val replicates = Config.int("ppo.checkReplicates", 2)
    // (Daemon threads: a run that ends is not kept alive by its check's pool.)
    private val pool = Executors.newFixedThreadPool(Config.int("ppo.checkParallel", 10)) { r -> Thread(r, "eco-check").also { it.isDaemon = true } }
    /** The networks each role plays with outside training, and that the new ones are judged against. */
    val kept = File(out, "kept")

    class Verdict(val round: Int, val ecosystem: Boolean, val why: List<String>, val roles: Map<Role, Boolean>, val lines: List<String>)

    /** The kept networks start as the ones the run starts from. */
    fun init() { if (!File(kept, Role.HERDS.file(kept).name).exists()) copyRoles(out, kept, Role.entries) }

    /** Checks the networks in [candidate] (a copy of round [round]'s, counted from 0) against the kept ones, beside the training. */
    fun start(round: Int, candidate: File): CompletableFuture<Verdict> = CompletableFuture.supplyAsync {
        val dir = File(out, "checks/round-${round + 1}").also { it.mkdirs() }
        val arms = linkedMapOf("new" to Role.entries.associateWith { candidate }, "kept" to Role.entries.associateWith { kept })
        for (r in Role.entries) arms["kept-$r"] = Role.entries.associateWith { if (it == r) kept else candidate }
        val runs = arms.mapValues { (name, from) ->
            val armDir = File(dir, name).also { it.mkdirs() }
            for ((r, src) in from) copyRoles(src, armDir, listOf(r))
            val cfg = File(armDir, "check.properties").also { it.writeText("nets=${armDir.absolutePath}\nways=${File(armDir, "ways.mlp").absolutePath}\n") }
            (0 until replicates).map { r -> pool.submit<File> { runOne(cfg, armDir, r) } }
        }.mapValues { (_, fs) -> fs.map { TestWorlds.Run(it.get()) } }
        judge(round, runs)
    }

    private fun runOne(cfg: File, armDir: File, r: Int): File {
        val history = File(armDir, "r$r.csv")
        val javaBin = ProcessHandle.current().info().command().orElse("java")
        val p = ProcessBuilder(listOf(javaBin, "-Xmx${Config.int("ppo.checkHeapGb", 12)}g", "-Dhex.config=${cfg.absolutePath}", "-Duser.home=${System.getProperty("user.home")}",
            "-cp", System.getProperty("java.class.path"), "inflation.tools.HexFaunaCheckKt", "--world", world.name, "--years", "$years", "--replicate", "$r", "--history", history.path))
            .redirectErrorStream(true).redirectOutput(File(armDir, "r$r.txt")).start()
        require(p.waitFor() == 0) { "check run failed: see ${File(armDir, "r$r.txt")}" }
        return history
    }

    private fun lastYear(rs: List<TestWorlds.Run>, key: String) = rs.map { it.yearly(key, it.years.last()) }.filter { !it.isNaN() }.average()

    private fun judge(round: Int, runs: Map<String, List<TestWorlds.Run>>): Verdict {
        val new = runs.getValue("new"); val kept = runs.getValue("kept")
        val kinds = new.first().allKeys.filter { it.startsWith("animals/") }.map { it.removePrefix("animals/") }
        val why = ArrayList<String>()
        val lines = ArrayList<String>()
        val cells = ArrayList<String>()
        for (k in kinds) {
            val n = lastYear(new, "animals/$k"); val o = lastYear(kept, "animals/$k")
            if (o > 0.0) cells += "%s %+.1f%%".format(k, 100 * (n / o - 1))
            if (o > 0.0 && n <= 0.0) why += "$k gone"
            else if (o > 0.0 && n < (1 - FALL) * o) why += "$k %+.0f%%".format(100 * (n / o - 1))
        }
        val migratory = Catalogue.migratory.map { it.name }
        val km = { rs: List<TestWorlds.Run> -> migratory.map { lastYear(rs, "migration/$it/km from half a year before") }.filter { !it.isNaN() }.average() }
        val kmNew = km(new); val kmKept = km(kept)
        if (!kmNew.isNaN() && !kmKept.isNaN() && kmNew < (1 - SHORTER) * kmKept) why += "herds stand %.0f km from half a year before, against %.0f".format(kmNew, kmKept)
        val people = Catalogue.HUMAN.name
        if (lastYear(new, "animals/$people") < lastYear(kept, "animals/$people")) why += "fewer people"
        val ecosystem = why.isEmpty()
        lines += "check round %d ecosystem (%s, %d years, %d weathers; last year, every role new against every role kept): %s; migratory herds %.0f -> %.0f km from half a year before -> %s".format(
            round + 1, world.name, years, replicates, cells.joinToString(", "), kmKept, kmNew, if (ecosystem) "holds" else "fails: " + why.joinToString(", "))
        val roles = Role.entries.associateWith { r ->
            val own = Catalogue.learned.filter { Role.of(it) == r }.map { it.name }
            val withNew = own.sumOf { lastYear(new, "animals/$it").let { v -> if (v.isNaN()) 0.0 else v } }
            val withKept = own.sumOf { lastYear(runs.getValue("kept-$r"), "animals/$it").let { v -> if (v.isNaN()) 0.0 else v } }
            val better = withNew > withKept
            lines += "check round %d %s: own kinds %.0f with the new network against %.0f with the kept one (%+.1f%%), the others new -> %s".format(
                round + 1, r, withNew, withKept, if (withKept > 0) 100 * (withNew / withKept - 1) else 0.0, if (better && ecosystem) "kept" else "not kept")
            better && ecosystem
        }
        return Verdict(round, ecosystem, why, roles, lines)
    }

    /** Applies [v]: copies each role's new network that passed from [candidate] into the kept ones. */
    fun apply(v: Verdict, candidate: File) { copyRoles(candidate, kept, v.roles.filterValues { it }.keys) }

    companion object {
        /** How far a kind may fall below its numbers with the kept networks, and the migratory herds' stand from half a year before. */
        const val FALL = 0.10
        const val SHORTER = 0.05

        /** Copies [roles]' networks, with their side files (and for people, the bands' ways), from [from] to [to]. */
        fun copyRoles(from: File, to: File, roles: Collection<Role>) {
            to.mkdirs()
            for (r in roles) {
                val prefixes = listOf(r.label + ".mlp") + if (r == Role.PEOPLE) listOf("ways.mlp") else emptyList()
                from.listFiles()?.filter { f -> f.isFile && prefixes.any { f.name.startsWith(it) } }?.forEach { it.copyTo(File(to, it.name), overwrite = true) }
            }
        }
    }
}
