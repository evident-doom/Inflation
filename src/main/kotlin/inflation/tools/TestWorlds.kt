package inflation.tools

import inflation.Config
import inflation.ecology.HexHistory
import inflation.run.TestWorlds
import inflation.run.gitCommit
import inflation.world.WorldCache
import java.io.File
import java.util.Properties

/** The testWorld program: see [TestWorlds]. */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "list" -> TestWorlds.list()
        "build" -> {
            var years = TestWorlds.SETTLE_YEARS
            var commit: String? = null
            val names = ArrayList<String>()
            var k = 1
            while (k < args.size) { when (args[k]) { "--years" -> years = args[++k].toInt(); "--commit" -> commit = args[++k]; else -> names += args[k] }; k++ }
            require(names.isNotEmpty()) { "build which? ${TestWorlds.ALL.joinToString(", ") { it.name }}" }
            for (n in names) build(TestWorlds.named(n), years, commit ?: gitCommit())
        }
        "ab" -> {
            var variant: File? = null; var base: File? = null; var worlds = TestWorlds.FULL; var years = 3; var replicates = 2; var parallel = 1
            var out = File("runs/ab-" + java.time.LocalDateTime.now().withNano(0).toString().replace(":", ""))
            var k = 1
            while (k < args.size) {
                when (args[k]) {
                    "--variant" -> variant = File(args[++k]); "--base" -> base = File(args[++k])
                    "--worlds" -> worlds = args[++k].split(',').map { TestWorlds.named(it.trim()) }
                    "--years" -> years = args[++k].toInt(); "--replicates" -> replicates = args[++k].toInt()
                    "--parallel" -> parallel = args[++k].toInt(); "--out" -> out = File(args[++k])
                    else -> error("unknown argument ${args[k]}")
                }
                k++
            }
            TestWorlds.ab(requireNotNull(variant) { "ab needs --variant FILE (config keys of the change)" }, base, worlds, years, replicates, parallel, out)
        }
        "ab-report" -> {
            // ab-report DIR [--worlds a,b] [--replicates N]: the report again from runs already made.
            val out = File(args[1]); var worlds = TestWorlds.FULL.map { it.name }; var replicates = 2
            var k = 2
            while (k < args.size) { when (args[k]) { "--worlds" -> worlds = args[++k].split(','); "--replicates" -> replicates = args[++k].toInt(); else -> error("unknown argument ${args[k]}") }; k++ }
            TestWorlds.abReport(out, worlds, replicates, "the variant")
        }
        "compare" -> {
            var full = emptyList<TestWorlds.Run>(); var fullVariant = emptyList<TestWorlds.Run>()
            val tests = ArrayList<Triple<String, TestWorlds.Run, TestWorlds.Run?>>()
            fun runs(s: String) = s.split(',').filter { it.isNotBlank() }.map { TestWorlds.Run(File(it)) }
            var k = 1
            while (k < args.size) {
                when {
                    args[k] == "--full" -> full = runs(args[++k])
                    args[k] == "--full-variant" -> fullVariant = runs(args[++k])
                    '=' in args[k] -> { val (n, files) = args[k].split('=', limit = 2); val r = runs(files); tests += Triple(n, r[0], r.getOrNull(1)) }
                    else -> error("unknown argument ${args[k]}")
                }
                k++
            }
            TestWorlds.compare(full, fullVariant, tests)
        }
        else -> println("usage: testWorld list | testWorld build NAME... [--years N] [--commit HASH] | testWorld ab --variant FILE [--base FILE] [--worlds full-1,full-2,full-3] [--years 3] [--replicates 2] [--parallel 1] [--out DIR] | testWorld ab-report DIR | testWorld compare --full A.csv,B.csv [--full-variant A2.csv,B2.csv] NAME=BASE.csv[,VARIANT.csv] ...\n" +
            "a test on a settled world: hexFauna --world NAME --years N [--replicate R] [--history FILE]")
    }
}

/** Settles test world [e] for [years] years and saves it, with its history and how it was made. */
private fun build(e: TestWorlds.Entry, years: Int, commit: String = gitCommit()) {
    e.dir.mkdirs()
    val building = File(e.dir, "building.bin")
    val t0 = System.nanoTime()
    faunaCheck(arrayOf("--seed", "${e.seed}", "--width", "${e.widthKm}", "--height", "${e.heightKm}", "--years", "$years", "--save", building.path, "--history", e.settle.path))
    val seconds = (System.nanoTime() - t0) / 1e9
    if (e.state.exists()) e.state.delete()
    require(building.renameTo(e.state)) { "could not move $building to ${e.state}" }
    val area = HexHistory.readCsv(e.settle).series[HexHistory.Key.LAND_AREA]?.let { it[it.size - 1] } ?: Float.NaN
    val p = Properties()
    p["name"] = e.name; p["seed"] = "${e.seed}"; p["widthKm"] = "${e.widthKm}"; p["heightKm"] = "${e.heightKm}"
    p["years"] = "$years"; p["commit"] = commit; p["built"] = java.time.LocalDateTime.now().withNano(0).toString()
    p["worldKey"] = "%016x".format(WorldCache.key(e.config)); p["config"] = Config.set("")
    p["landKm2"] = "%.0f".format(area); p["buildSeconds"] = "%.0f".format(seconds)
    p["secondsPerYear"] = "%.1f".format(seconds / years)
    e.about.writer().use { p.store(it, "test world ${e.name}, written by testWorld build") }
    println("test world ${e.name} settled for $years years in %.0f s (%.1f s a year, with history and saving): ${e.state} (%.0f MB)".format(seconds, seconds / years, e.state.length() / 1e6))
}
