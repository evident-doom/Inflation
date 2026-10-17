package inflation

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Run settings, read from a properties file of keys (see config.example.properties): the file
 * -Dhex.config=FILE names (none: no file), else inflation.properties in the working directory, else
 * none (every key at its default). A setting left out keeps its default; tuning keys start with "t.".
 *
 * A tuning key set in the file that the run never reads (misspelt, or of a part that did not run) is
 * named on stderr as the run ends.
 *
 * The old -Dhex.* switches are no longer read: a run given one stops at once and names the key to
 * set instead, so that no run goes on silently without a setting it was meant to have.
 */
object Config {
    /** Old switches and the key that now sets them, where it is not the bare name (keys say what is on, not what is off). */
    private val RENAMED = mapOf("rounds" to "rounds=true", "taming" to "taming=true", "noPeople" to "people=false", "noWorldCache" to "worldCache=false",
        "noLandformCache" to "landformCache=false")
    /** Old switches that are gone: their setting is now the code's. */
    private val GONE = setOf("trade", "t.noGifts", "t.noSourceRites", "noHerding", "t.noDenning", "t.herdRanges", "t.noProtein",
        "t.noGreenBearing", "noBearings", "t.bandContentWeeks", "t.spanFloor", "t.ambush", "migrantUnit", "migrantStart", "sizeByLeaning",
        "newcomers", "catalogueStart", "t.roundCue", "t.roundSpread", "t.roundFollowGreen", "t.supersense", "t.superTop", "t.superKm",
        "t.superLure", "t.scoutKm", "t.scoutChance", "t.scoutMargin", "t.plateauKm", "t.plateauM")

    private val props = Properties()
    /** The keys set in the file that the run has read. */
    private val read = ConcurrentHashMap.newKeySet<String>()
    val source: File?

    init {
        val named = System.getProperty("hex.config")
        source = if (named == "none") null else named?.let { File(it) } ?: File("inflation.properties").takeIf { it.isFile }
        if (source != null) {
            require(source.isFile) { "config file $source not found (-Dhex.config)" }
            source.reader().use { props.load(it) }
        }
        val stray = System.getProperties().stringPropertyNames().filter { it.startsWith("hex.") && it != "hex.config" }.map { it.removePrefix("hex.") }.sorted()
        require(stray.isEmpty()) {
            val (gone, moved) = stray.partition { it in GONE }
            "the -Dhex.* switches are no longer read." +
                (if (moved.isEmpty()) "" else " Set these in the config file instead (see config.example.properties): " +
                    moved.joinToString(", ") { RENAMED[it] ?: it } + ".") +
                (if (gone.isEmpty()) "" else " These are gone, their default now being the code: " + gone.joinToString(", ") + ".")
        }
        val unknown = props.stringPropertyNames().filter { it in GONE || it.startsWith("no") && it in RENAMED }
        require(unknown.isEmpty()) { "config keys no longer read: ${unknown.joinToString(", ")} (see config.example.properties)" }
        if (props.stringPropertyNames().any { it.startsWith("t.") }) Runtime.getRuntime().addShutdownHook(Thread {
            unread("t.").takeIf { it.isNotEmpty() }?.let { System.err.println(
                "tuning keys set but never read in this run (misspelt, or of a part that did not run): ${it.joinToString(", ")}") }
        })
    }

    fun string(key: String): String? = props.getProperty(key)?.also { read += key }?.trim()?.takeIf { it.isNotEmpty() }
    fun flag(key: String, default: Boolean): Boolean = string(key)?.toBooleanStrict() ?: default
    fun float(key: String, default: Float): Float = string(key)?.toFloat() ?: default
    fun double(key: String, default: Double): Double = string(key)?.toDouble() ?: default
    fun int(key: String, default: Int): Int = string(key)?.toInt() ?: default

    /** The keys set under a prefix that the run has not read (so far). */
    fun unread(prefix: String): List<String> = props.stringPropertyNames().filter { it.startsWith(prefix) && it !in read }.sorted()

    /** The keys set under a prefix, as "name value" (for a check's header). */
    fun set(prefix: String): String = props.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()
        .joinToString(", ") { "${it.removePrefix(prefix)} ${string(it)}" }
}
