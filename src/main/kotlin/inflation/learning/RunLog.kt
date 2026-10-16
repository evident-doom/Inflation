package inflation.learning

import java.io.File

/**
 * A training run's log in [dir]: log.txt with a line per [entry] done ("round", "gen"), so that a run
 * started again in the same directory goes on from where the last one stopped; and check.txt, the checks.
 */
class RunLog(dir: File, private val entry: String) {
    private val log = File(dir, "log.txt")
    private val checks = File(dir, "check.txt")

    /** How many [entry] lines earlier runs logged: the number of the next one. */
    fun done(): Int = if (log.exists()) log.readLines().count { it.startsWith("$entry ") } else 0

    /** Prints [line] and adds it to the log. */
    fun write(line: String) { println(line); log.appendText(line + "\n") }

    /** Prints [line] and adds it to the checks. */
    fun check(line: String) { println(line); checks.appendText(line + "\n") }
}
