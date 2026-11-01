package inflation

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.DeathCause
import inflation.ecology.HuntStep
import inflation.ecology.Met
import inflation.ecology.Tally
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps dynamic-style code from coming back (see review/2026-10-20-keeping-string-keys-out.md and
 * review/2026-10-20-dynamic-style.md): a map keyed by text, a key matched by its text, a `!!`, a cast, an `Any` or
 * a reflective call is a place where the compiler cannot tell a right use from a wrong one. The ones there are on
 * purpose are counted here per file with why; a new one fails until it is typed or listed.
 * (Comment lines and imports are not counted.) Places go as `Place`, hexes as `Hex`, resident kinds as `FaunaKind`: a parameter named `p` or `q` that is a plain `Int` fails here too.
 */
class StringKeysTest {
    private val root = File("src/main/kotlin/inflation")

    private fun code(f: File) = f.readLines().filterNot { l -> l.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("import ") } }.joinToString("\n")

    /** Count of [pattern] in the code of each file under [dirs] (paths relative to [root]). */
    private fun counts(pattern: Regex, vararg dirs: String): Map<String, Int> =
        dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.distinct()
            .associate { f -> f.relativeTo(root).invariantSeparatorsPath to pattern.findAll(code(f)).count() }.filterValues { it > 0 }

    private fun check(pattern: String, allowed: Map<String, Pair<Int, String>>, what: String, hint: String, vararg dirs: String) {
        val found = counts(Regex(pattern), *dirs)
        val wrong = (found.keys + allowed.keys).filter { (found[it] ?: 0) != (allowed[it]?.first ?: 0) }.sorted()
        assertTrue(wrong.isEmpty(), "$what: count changed in " + wrong.joinToString { "$it (found ${found[it] ?: 0}, listed ${allowed[it]?.first ?: 0})" } +
            ". $hint, or list it here with why it must stay.")
    }

    @Test
    fun `no new maps keyed by text`() = check("""(?:Map|SkipListMap|HashMap|TreeMap)<String,""", mapOf(
        "Books.kt" to (7 to "flow names, matter by stock name and part: printed by the books, never read back by key"),
        "store/ObjectStore.kt" to (2 to "field names of saved objects, from reflection"),
        "ui/ViewLayers.kt" to (5 to "the viewer's layer names, shown as text"),
        "tools/fauna/Tallies.kt" to (4 to "causes and readers' sums by the text after the kind, for printing"),
        "tools/HexBandLuck.kt" to (1 to "people's deaths by cause, for printing"),
        "ecology/HexWater.kt" to (2 to "fish flows by name, printed and put in the history whole"),
        "ecology/Catalogue.kt" to (1 to "settled densities by kind name, read from a properties file"),
        "ecology/Band.kt" to (1 to "Band fields by name, checked by BandCarryTest"),
        "ecology/BandCarry.kt" to (2 to "Band fields by name, checked by BandCarryTest"),
        "ecology/Contracts.kt" to (4 to "Herd and Dogs fields by name, checked by BandCarryTest"),
        "Profile.kt" to (5 to "phase timings, and the slowest jobs and their counts, by phase name"),
        "tools/ProfileReport.kt" to (11 to "a recording's phases, loops, labels, frames and waits by name, for printing; labels by class and method name, read from the @Part annotations"),
        "ecology/HexHistory.kt" to (5 to "history series: the CSV columns are text"),
        "run/TestWorlds.kt" to (1 to "runs by test-world name, user input"),
        "run/Nominal.kt" to (1 to "history series grouped by kind name, as the CSV columns name them"),
        "learning/Critic.kt" to (2 to "each kind's return scale by kind name, saved with the net"),
        "learning/EcoCheck.kt" to (1 to "test runs by world name, user input"),
        "learning/HexPpo.kt" to (1 to "non-finite net outputs by kind and what, for the round log"),
    ), "maps keyed by String", "Type the new key (an enum, Species, TallyKey...)", ".")

    @Test
    fun `the model matches no keys by their text`() = check("""\.(?:startsWith|endsWith|substringAfter|substringBefore|removePrefix|removeSuffix)\(""", mapOf(
        "ecology/HexHistory.kt" to (20 to "history series by prefix: the CSV columns are text (reports only)"),
        "ecology/WeekStats.kt" to (8 to "the Tally builders' own parsing"),
        "ecology/HuntDiag.kt" to (2 to "the hunt steps' own prefix"),
        "ecology/HexSnapshot.kt" to (2 to "class names when saving"),
        "ecology/HexGeography.kt" to (1 to "a name for a landmark"),
        "world/FreeAir.kt" to (1 to "comment lines of a data file"),
        "world/WorldCaches.kt" to (6 to "class and file names for the world key"),
    ), "matching by text in ecology/ and world/", "Type the key", "ecology", "world")

    @Test
    fun `no new non-null assertions`() = check("""!!""", mapOf(
        "ecology/HexCulture.kt" to (5 to "a band of a people group: the caller has chosen people"),
        "Profile.kt" to (1 to "the jar's own profile.jfc resource, which ships with the code"),
        "ecology/HexFauna.kt" to (4 to "audit books set when the audit is on and tested just before"),
        "ecology/HexGeography.kt" to (1 to "the cover of a core, set while building"),
        "ecology/HexGroups.kt" to (3 to "the path and time of a group that chose to move"),
        "ecology/GroupPlanner.kt" to (1 to "the reach cache merge"),
        "ecology/HexLifeAndDeath.kt" to (1 to "a band of a people group: the caller has chosen people"),
        "ecology/HexPeople.kt" to (2 to "a band of a people group: the caller has chosen people"),
        "ecology/HexSmallLife.kt" to (1 to "the months a bird is present: set for the kinds that follow insects"),
        "learning/PpoTrainer.kt" to (1 to "a player is made for every world before the round"),
        "run/Nominal.kt" to (3 to "the kind or way of a series key already tested as that sort of key"),
        "tools/HexChoiceEffect.kt" to (1 to "a map entry tested just before"),
        "tools/fauna/Census.kt" to (3 to "the band of a people group in a report"),
        "tools/fauna/Tallies.kt" to (5 to "the band of a people group in a report"),
        "ui/GroupControlWindow.kt" to (1 to "Map.merge of a non-null value"),
        "ui/WrapLayout.kt" to (1 to "Swing's parent chain"),
        "world/FreeAir.kt" to (1 to "a resource that ships in the jar"),
        "world/HexPlants.kt" to (3 to "audit books set when the audit is on and tested just before"),
    ), "`!!`", "Make the type say it (a non-null field, a smart cast, a typed accessor)", ".")

    @Test
    fun `no casts in the model`() = check("""\bas\s+[A-Z]""", mapOf(
        "store/ObjectStore.kt" to (9 to "saves are by field name and runtime type: the one reflective store; hash maps and sets written in key order"),
        "ui/HexHistoryPanel.kt" to (7 to "Swing's tree and component API"),
        "ui/HexViewer.kt" to (2 to "Swing's caret API"),
        "ui/WorldLock.kt" to (1 to "Swing's Graphics"),
        "world/WorldCaches.kt" to (1 to "the cache reader returns what it is asked for"),
    ), "casts", "Use a typed value or a sealed type", ".")

    @Test
    fun `no new Any`() = check("""(?::\s*Any\??\b|<Any\??>|vararg \w+: Any)""", mapOf(
        "Books.kt" to (4 to "the stock registry takes the tables it sums, of several array types, only for the audit"),
        "run/Nominal.kt" to (1 to "walks a part's tables by reflection"),
        "store/ObjectStore.kt" to (18 to "saves are by field name and runtime type; boxed value classes go as the value they wrap; hash map keys ordered as they come"),
        "ui/GroupControlWindow.kt" to (1 to "Swing's table model"),
        "ui/HexHistoryPanel.kt" to (1 to "Swing's tree nodes"),
        "world/WorldCaches.kt" to (1 to "the cache reader"),
    ), "`Any`", "Give it a type", ".")

    @Test
    fun `no new reflection`() = check("""java\.lang\.reflect|::class\.java|getDeclaredField|\.declaredFields|Class\.forName|javaClass""", mapOf(
        "ecology/Catalogue.kt" to (1 to "the kinds' list, read from the file's own object"),
        "ecology/HexSnapshot.kt" to (21 to "saves are by field name: the order and roster checks"),
        "run/Nominal.kt" to (4 to "lists a part's tables for the check"),
        "Profile.kt" to (4 to "profile=jfr registers the model's own event classes and reads the jar's profile.jfc"),
        "tools/ProfileReport.kt" to (4 to "finds the @Part labels by reflection over the program's own classes"),
        "store/ObjectStore.kt" to (52 to "saves are by field name and runtime type; boxed value classes are boxed again by the declared type; a hash map's keys sorted by their runtime class"),
        "world/FreeAir.kt" to (1 to "a resource that ships in the jar"),
        "world/WorldCaches.kt" to (2 to "the jar's resources and the cache reader"),
    ), "reflection", "Use a typed interface", ".")

    @Test
    fun `places are passed as Place, not as Int`() = check("""[(, ][pq]: Int\b""", mapOf(
        "ecology/HexFauna.kt" to (2 to "the resident flow tables are Int arrays: a neighbour's index and one way of a flow"),
        "ecology/GroupPlanner.kt" to (2 to "pathTo takes a candidate place from an Int list; where() is a debug text"),
        "ecology/GroupFood.kt" to (2 to "the places a group swept, kept in an Int array of the week's sweep"),
        "world/Weather.kt" to (2 to "the weather serves the fine grid and the places: one index type would be wrong"),
        "world/HexHydrology.kt" to (2 to "the glacier flow's places in a hex's Int list"),
        "world/HexLand.kt" to (2 to "mean over a place's cells, used by the array initialisers"),
    ), "places as Int parameters", "Take a Place (world/Indices.kt)", "ecology", "world")

    @Test
    fun `hexes are passed as Hex, not as Int`() = check("""[(, ](h|hh|nh|h0|h1|here|home|\w*Hex): Int\b|\b(val|var) (\w*[hH]ex\w*|here|home)(: Int\b| = IntArray\b)""", mapOf(
        "ui/HexHistoryPanel.kt" to (2 to "a chart's width and height, not a hex"),
    ), "hexes as Int", "Take a Hex, or a HexArray for several (world/Indices.kt)", ".")

    @Test
    fun `hexes are made from Ints only where an index is turned into one`() = check("""\bHex\(""", mapOf(
        "world/Indices.kt" to (20 to "the index types themselves: iterators and arrays turn their ints into hexes"),
        "world/HexMap.kt" to (7 to "the map's own layout: a column and row, a point, and the per-hex builders"),
        "ecology/HexCulture.kt" to (1 to "the hexes a band knows are kept as bits"),
        "ecology/GroupPlanner.kt" to (1 to "the reach search's heap packs effort and hex into one long"),
        "ecology/HexLifeAndDeath.kt" to (1 to "outbreaks starting are keyed by kind and hex in one long"),
        "ecology/HexRoutes.kt" to (2 to "lake shares keyed by lake and hex in one long; the first hex of a row, for the landmarks' search"),
        "tools/fauna/Tallies.kt" to (1 to "a per-hex table walked by its index"),
        "ui/HexViewer.kt" to (1 to "a layer's per-hex values walked by their index"),
    ), "Hex made from an Int", "Take the Hex from the map (HexMap.all, neighbour, owner, HexWorld.placeHex)", ".")

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
        unique("Tally.Squeeze", Tally.Squeeze.entries.map { it.label })
        unique("Tally.Spare", Tally.Spare.entries.map { it.label })
        unique("Tally.HerdKept", Tally.HerdKept.entries.map { it.label })
        unique("Tally.Warmed", Tally.Warmed.entries.map { it.label })
        unique("Tally.Hut", Tally.Hut.entries.map { it.label })
        unique("Tally.FoodTime", Tally.FoodTime.entries.map { it.label })
        unique("Tally.ClothesMade", Tally.ClothesMade.entries.map { it.label })
    }
}
