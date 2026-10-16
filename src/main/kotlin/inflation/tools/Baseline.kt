package inflation.tools

import inflation.Args
import inflation.Config

import inflation.learning.Role
import inflation.learning.Schema
import inflation.learning.SharedMover
import java.io.File

/**
 * The registry of baselines: trained networks kept, frozen and named, to compare later changes
 * against and to continue training from (see docs/design/model-compatibility.md). A baseline lives in
 * runs/baselines/NAME: the role networks (herds, hunters, people, each with its .identity and
 * .names), the bands' ways network if there is one, and info.txt (when it was kept, from what, the
 * commit it was trained at, and a note: settings, seeds and the check's record line).
 *
 * Usage:
 *   baseline save NAME FROM_DIR [--ways FILE] [--commit HASH] [--note TEXT]
 *   baseline list
 *   baseline show NAME      (loads each network into today's model, by name, and says what it lacks)
 */
fun main(args: Array<String>) {
    val root = File(Config.string("baselines") ?: "runs/baselines")
    when (args.getOrNull(0)) {
        "save" -> {
            val name = args[1]; val from = File(args[2])
            val a = Args(args.copyOfRange(3, args.size), "baseline save NAME FROM_DIR [--ways FILE] [--commit HASH] [--note TEXT]")
            val ways = a.file("--ways"); val commit = a.string("--commit") ?: gitCommit(); val note = a.string("--note") ?: ""
            a.done()
            val dir = File(root, name)
            require(!dir.exists()) { "baseline $name exists already: baselines are kept as they are" }
            dir.mkdirs()
            for (r in Role.entries) {
                val f = r.file(from); if (!f.exists()) continue
                // Loaded and saved again, so it carries its names.
                SharedMover.load(f).save(r.file(dir))
            }
            ways?.let { Schema.save(Schema.load(it, Schema.WAYS_IN, Schema.WAYS_OUT), File(dir, "ways.mlp"), Schema.WAYS_IN, Schema.WAYS_OUT) }
            File(dir, "info.txt").writeText("kept ${java.time.LocalDateTime.now()}\nfrom ${from.path}${ways?.let { " and ${it.path}" } ?: ""}\ncommit $commit\n$note\n")
            println("kept baseline $name in $dir")
        }
        "list" -> root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            println("%-24s %s".format(d.name, File(d, "info.txt").takeIf { it.exists() }?.readLines()?.joinToString(" | ") ?: ""))
        }
        "show" -> {
            val dir = File(root, args[1])
            File(dir, "info.txt").takeIf { it.exists() }?.let { println(it.readText().trim()) }
            for (r in Role.entries) { val f = r.file(dir); if (f.exists()) report(r.label, f, if (r == Role.PEOPLE) Schema.PEOPLE_IN else Schema.MOVER_IN, Schema.MOVER_OUT) }
            File(dir, "ways.mlp").takeIf { it.exists() }?.let { report("ways", it, Schema.WAYS_IN, Schema.WAYS_OUT) }
        }
        else -> println("usage: baseline save NAME FROM_DIR [--ways FILE] [--commit HASH] [--note TEXT] | baseline list | baseline show NAME")
    }
}

/** Where [name] is a baseline's name, its directory; otherwise [name] as a path. */
fun baselineDir(name: String): File = File(name).takeIf { it.exists() } ?: File(Config.string("baselines") ?: "runs/baselines", name)

private fun report(what: String, f: File, inputs: List<String>, outputs: List<String>) {
    val nf = Schema.namesFile(f)
    val (si, so) = if (nf.exists()) nf.readLines().let { it[0].split("|") to it[1].split("|") } else emptyList<String>() to emptyList()
    val newIn = inputs.filter { it !in si }; val goneIn = si.filter { it !in inputs }
    val newOut = outputs.filter { it !in so }; val goneOut = so.filter { it !in outputs }
    println("$what: ${si.size} inputs, ${so.size} outputs saved; new to it (ignored until trained): " +
        (newIn + newOut).joinToString(", ").ifEmpty { "none" } + "; no longer in the model (dropped): " + (goneIn + goneOut).joinToString(", ").ifEmpty { "none" })
}

internal fun gitCommit(): String = try {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (e: Exception) { "unknown" }
