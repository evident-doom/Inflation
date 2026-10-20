package inflation

import inflation.ecology.DeathCause
import inflation.ecology.HuntStep
import inflation.ecology.Met
import inflation.ecology.Tally
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps string keys from coming back (see review/2026-10-20-keeping-string-keys-out.md): a map keyed by text, or a
 * key matched by its text in the model, is a place where a writer and a reader can drift apart with nothing failing.
 * The ones there are on purpose are counted here per file with why; a new one fails until it is typed or listed.
 */
class StringKeysTest {
    private val root = File("src/main/kotlin/inflation")

    /** Count of [pattern] in each file under [dirs] (paths relative to [root]). */
    private fun counts(pattern: Regex, vararg dirs: String): Map<String, Int> =
        dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.distinct()
            .associate { f -> f.relativeTo(root).invariantSeparatorsPath to pattern.findAll(f.readText()).count() }.filterValues { it > 0 }

    private fun check(found: Map<String, Int>, allowed: Map<String, Pair<Int, String>>, what: String) {
        val wrong = (found.keys + allowed.keys).filter { (found[it] ?: 0) != (allowed[it]?.first ?: 0) }.sorted()
        assertTrue(wrong.isEmpty(), "$what: count changed in " + wrong.joinToString { "$it (found ${found[it] ?: 0}, listed ${allowed[it]?.first ?: 0})" } +
            ". Type the new key (an enum, Species, TallyKey...), or list it here with why it must be text.")
    }

    @Test
    fun `no new maps keyed by text`() {
        val maps = Regex("""(?:Map|SkipListMap|HashMap|TreeMap)<String,""")
        check(counts(maps, "."), mapOf(
            "Books.kt" to (7 to "flow names, matter by stock name and part: printed by the books, never read back by key"),
            "store/ObjectStore.kt" to (2 to "field names of saved objects, from reflection"),
            "ui/ViewLayers.kt" to (5 to "the viewer's layer names, shown as text"),
            "tools/fauna/Tallies.kt" to (4 to "causes and readers' sums by the text after the kind, for printing"),
            "tools/HexBandLuck.kt" to (1 to "people's deaths by cause, for printing"),
            "ecology/WeekStats.kt" to (1 to "children's expected deaths by cause label, for printing"),
            "ecology/HexWater.kt" to (2 to "fish flows by name, printed and put in the history whole"),
            "ecology/Catalogue.kt" to (1 to "settled densities by kind name, read from a properties file"),
            "ecology/HexPeople.kt" to (1 to "Band fields by name, checked by BandCarryTest"),
            "ecology/BandCarry.kt" to (2 to "Band fields by name, checked by BandCarryTest"),
            "ecology/Profile.kt" to (3 to "phase timings by phase name"),
            "ecology/HexHistory.kt" to (5 to "history series: the CSV columns are text"),
            "run/TestWorlds.kt" to (1 to "runs by test-world name, user input"),
            "run/Nominal.kt" to (1 to "history series grouped by kind name, as the CSV columns name them"),
            "learning/Critic.kt" to (2 to "each kind's return scale by kind name, saved with the net"),
            "learning/EcoCheck.kt" to (1 to "test runs by world name, user input"),
            "learning/HexPpo.kt" to (1 to "non-finite net outputs by kind and what, for the round log"),
        ), "maps keyed by String")
    }

    @Test
    fun `the model matches no keys by their text`() {
        val matching = Regex("""\.(?:startsWith|endsWith|substringAfter|substringBefore|removePrefix|removeSuffix)\(""")
        check(counts(matching, "ecology", "world"), mapOf(
            "ecology/HexHistory.kt" to (17 to "history series by prefix: the CSV columns are text (reports only)"),
            "ecology/WeekStats.kt" to (8 to "the Tally builders' own parsing"),
            "ecology/HuntDiag.kt" to (2 to "the hunt steps' own prefix"),
            "ecology/HexSnapshot.kt" to (2 to "class names when saving"),
            "ecology/HexGeography.kt" to (1 to "a name for a landmark"),
            "world/FreeAir.kt" to (1 to "comment lines of a data file"),
            "world/WorldCaches.kt" to (6 to "class and file names for the world key"),
        ), "matching by text in ecology/ and world/")
    }

    @Test
    fun `key labels are each their own`() {
        fun unique(name: String, labels: List<String>) = assertEquals(labels.size, labels.toSet().size, "$name labels repeat: ${labels.groupBy { it }.filterValues { it.size > 1 }.keys}")
        unique("StockName", StockName.entries.map { it.label })
        unique("Config.Key", Config.Key.entries.map { it.key })
        unique("DeathCause", DeathCause.entries.map { it.label })
        unique("Met", Met.entries.map { it.label })
        unique("HuntStep", HuntStep.entries.map { it.label })
        unique("Tally.OfKind", Tally.OfKind.entries.map { it.label })
        unique("Tally.DietPart", Tally.DietPart.entries.map { it.label })
        unique("Tally.Bands", Tally.Bands.entries.map { it.label })
        unique("Tally.Cold", Tally.Cold.entries.map { it.label })
        unique("Tally.Clad", Tally.Clad.entries.map { it.label })
        unique("Tally.TimeTotal", Tally.TimeTotal.entries.map { it.label })
        unique("Tally.HideSource", Tally.HideSource.entries.map { it.label })
    }
}
