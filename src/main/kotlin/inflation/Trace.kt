package inflation

import java.util.concurrent.atomic.AtomicInteger

/**
 * A diagnostic of lines on stderr, printed while its config [key] is set: a [share] of the cases, drawn by a hash of the
 * case and not from the model's streams of chance (so that turning it on changes nothing in the run), and at most [most]
 * lines. With [byKind] the key names a kind (planDiag=wolf) and only that kind's cases are printed; else it is true or false.
 * Several traces may share a key: each is kept apart by its [name], its own share and its own count of lines.
 */
class Trace(
    key: Config.Key,
    private val name: String,
    private val share: Float = 1f,
    private val most: Int = Int.MAX_VALUE,
    byKind: Boolean = false,
    /** The key's value, as [Config] reads it (a test gives its own). */
    read: (Config.Key) -> String? = Config::string,
) {
    /** The kind the key names, with [byKind]. */
    private val kind = if (byKind) read(key) else null
    /** Whether the trace is on at all: for the work only it needs, done whether or not a case is drawn. */
    val on = if (byKind) kind != null else read(key)?.toBooleanStrict() ?: false
    private val salt = name.hashCode().toLong()
    private val lines = AtomicInteger()

    /**
     * Whether case [id] at [at] (and [part]) is printed: the trace is on, the case is of its kind ([of], with [byKind]),
     * one of the share drawn, and lines are left. A case it answers yes to counts as a line.
     */
    fun wants(id: Long, at: Long = 0L, of: String? = null, part: Long = 0L): Boolean {
        if (!on || (kind != null && of != kind)) return false
        if (share < 1f && drawUnit(0L, Stream.TRACE, id, at, salt + part) >= share) return false
        return lines.incrementAndGet() <= most
    }

    /** Prints the line [text] makes for case [id] at [at] (and [part]) if it is one [wants] answers yes to. */
    inline fun line(id: Long, at: Long = 0L, of: String? = null, part: Long = 0L, text: () -> String) {
        if (wants(id, at, of, part)) System.err.println(text())
    }

    override fun toString() = "trace $name"
}
