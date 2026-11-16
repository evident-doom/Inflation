package inflation.run

import java.io.File
import java.util.Properties

/**
 * The latest settled test world and the default nets, kept in the repository for machines that cannot reach the pod.
 *
 * They live on the branch [BRANCH] of origin, which holds one commit only: each update (scripts/publish-latest.sh, run by
 * the pod's runner after an overnight training or a resettle) makes a new commit without a parent and force-pushes it, so
 * the branch never gathers old worlds and nets; the commit it replaces is left for GitHub to drop. The branch holds:
 *   LATEST.properties              world=NAME, nets=NAME, and when and from what they were made
 *   test-worlds/NAME/state.bin     the world, as in ~/.inflation/test-worlds/NAME (git keeps files under 100 MB only)
 *   test-worlds/NAME/about.properties
 *   baselines/NAME/...             the nets, as in runs/baselines/NAME
 *
 * Nothing here is used where the local copies are: a test world whose state.bin is missing ([world]), a baseline named
 * but missing ([baseline]), and the default nets where runs/baselines holds none ([latestNets]) are fetched from the
 * branch, once a run. `testWorld latest` fetches both whether missing or not, so that a machine that has older ones
 * takes the newest (the newest baseline is the default nets, [inflation.learning.LearnedModel.latestBaseline]).
 * Git is run in the working directory, which is the repository's when the tools run from gradle.
 */
object LatestData {
    const val BRANCH = "latest-data"
    private const val REF = "refs/remotes/origin/$BRANCH"

    private var fetched: Boolean? = null

    /** Runs git with [args]; its output, or null where it fails. [to]: the file its output goes to instead. */
    private fun git(vararg args: String, to: File? = null): String? = try {
        val pb = ProcessBuilder(listOf("git") + args)
        if (to != null) pb.redirectOutput(to).redirectError(ProcessBuilder.Redirect.DISCARD) else pb.redirectErrorStream(true)
        val p = pb.start()
        val out = if (to == null) p.inputStream.bufferedReader().readText() else ""
        if (p.waitFor() == 0) out else null
    } catch (e: Exception) { null }

    /** Fetches the branch from origin, once a run; false where there is no git, no origin or no such branch. */
    fun fetch(): Boolean = fetched ?: run {
        println("fetching the latest world and nets from origin's $BRANCH branch")
        val got = git("fetch", "--quiet", "--no-tags", "origin", "+refs/heads/$BRANCH:$REF") != null
        if (!got) println("WARNING: could not fetch origin's $BRANCH; using the copy fetched before, if any")
        val ok = got || git("rev-parse", "--verify", "--quiet", REF) != null
        if (!ok) println("WARNING: no $BRANCH branch to be had (no git, no origin, or the branch is not there)")
        fetched = ok; ok
    }

    /** The branch's LATEST.properties, or null. */
    fun about(): Properties? = if (!fetch()) null else git("cat-file", "blob", "$REF:LATEST.properties")?.let { s -> Properties().also { it.load(s.reader()) } }

    /** Writes the branch's [path] to [file] (by a file beside it, renamed when whole). */
    private fun copy(path: String, file: File): Boolean {
        file.parentFile.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        val ok = git("cat-file", "blob", "$REF:$path", to = part) != null && (!file.exists() || file.delete()) && part.renameTo(file)
        if (!ok) part.delete()
        return ok
    }

    /** The files of the branch's folder [dir], by their names. */
    private fun files(dir: String): List<String> = git("ls-tree", "--name-only", "$REF:$dir")?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    /** Where the branch holds test world [e] (whatever is kept locally), copies it to its folder; whether it did. */
    fun world(e: TestWorlds.Entry): Boolean {
        val a = about() ?: return false
        if (a.getProperty("world") != e.name) { println("$BRANCH holds test world ${a.getProperty("world")}, not ${e.name}"); return false }
        val ok = copy("test-worlds/${e.name}/about.properties", e.about) && copy("test-worlds/${e.name}/state.bin", e.state)
        println(if (ok) "test world ${e.name} fetched from $BRANCH: ${e.state} (%.0f MB)".format(e.state.length() / 1e6) else "WARNING: could not copy test world ${e.name} from $BRANCH")
        return ok
    }

    /** Where the branch holds baseline [name], copies it into the baselines' folder (unless there already); its folder there, or null. */
    fun baseline(name: String): File? {
        val dir = File(baselinesRoot(), name)
        if (dir.isDirectory) return dir
        val a = about() ?: return null
        if (a.getProperty("nets") != name) return null
        val names = files("baselines/$name")
        val ok = names.isNotEmpty() && names.all { copy("baselines/$name/$it", File(dir, it)) }
        if (!ok) { println("WARNING: could not copy baseline $name from $BRANCH"); dir.deleteRecursively(); return null }
        println("baseline $name fetched from $BRANCH: $dir")
        return dir
    }

    /** The branch's nets, copied into the baselines' folder (see [baseline]), or null. */
    fun latestNets(): File? = about()?.getProperty("nets")?.let { baseline(it) }

    /** `testWorld latest`: the branch's world (unless the local one was made the same way) and nets, copied into place. */
    fun sync() {
        val a = about() ?: error("no $BRANCH branch to fetch from origin")
        println("$BRANCH: " + a.stringPropertyNames().sorted().joinToString(", ") { "$it=${a.getProperty(it)}" })
        a.getProperty("world")?.let { name ->
            val e = TestWorlds.named(name)
            val theirs = git("cat-file", "blob", "$REF:test-worlds/$name/about.properties")?.let { s -> Properties().also { it.load(s.reader()) } }
            if (e.state.isFile && e.info() == theirs) println("test world $name is up to date") else world(e)
        }
        a.getProperty("nets")?.let { name -> if (File(baselinesRoot(), name).isDirectory) println("baseline $name is here already") else baseline(name) }
    }
}
