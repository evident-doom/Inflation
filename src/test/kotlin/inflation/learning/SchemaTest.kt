package inflation.learning

import inflation.ecology.BandWay
import inflation.ecology.FOOD_TYPES
import inflation.ecology.Food
import inflation.ecology.HexFeatures
import inflation.ecology.Tool
import inflation.ecology.WAYS_IN
import inflation.ecology.WAYS_OUT
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    private fun out(net: Mlp, x: FloatArray): FloatArray {
        val b = net.buffers(); x.copyInto(b.a[0]); return net.forwardAll(b).copyOf()
    }

    @Test
    fun aNetworkFittedToNewInputsAndOutputsChoosesAsBeforeAndIsNeutralOnTheNew() {
        val rnd = Random(3)
        val old = Mlp.random(intArrayOf(3, 5, 2), rnd)
        val oldIn = listOf("a", "b", "c"); val oldOut = listOf("x", "y")
        // "b" is gone, "d" is new (inserted in the middle), "z" is a new output.
        val newIn = listOf("a", "d", "c"); val newOut = listOf("y", "z", "x")
        val fitted = Schema.fit(old, oldIn, oldOut, newIn, newOut)
        repeat(20) {
            val a = Random.nextFloat(); val c = Random.nextFloat(); val d = Random.nextFloat()
            val before = out(old, floatArrayOf(a, 0f, c))
            val after = out(fitted, floatArrayOf(a, d, c))
            assertEquals(before[1], after[0], 1e-5f)
            assertEquals(before[0], after[2], 1e-5f)
            assertEquals(0f, after[1], 1e-6f)
        }
    }

    /** The named feature slots are the places of the names saved networks are fitted by: a slot that moved would feed a network the wrong input. */
    @Test
    fun theNamedPlaceFeaturesAreWhereTheSavedNamesSayTheyAre() {
        val named = listOf(
            HexFeatures.FOOD to "food",
            HexFeatures.CLIMATE to "climate suits",
            HexFeatures.CROWDING to "crowding",
            HexFeatures.HUNTERS to "hunters",
            HexFeatures.DISCOMFORT to "discomfort",
            HexFeatures.DISCOMFORT_FORECAST to "discomfort forecast",
            HexFeatures.SNOW_OVER_LIMIT to "snow over limit",
            HexFeatures.SNOW to "snow",
            HexFeatures.CLIMB to "climb",
            HexFeatures.EFFORT to "effort",
            HexFeatures.STAYS to "stays",
            HexFeatures.SAME_HEX to "same hex",
            HexFeatures.TOWARD_BEARING to "toward bearing",
            HexFeatures.SEASON_PLACE to "season's place",
            HexFeatures.FED to "fed",
            HexFeatures.FAT to "fat",
            HexFeatures.SEASON_SIN to "season sin",
            HexFeatures.SEASON_COS to "season cos",
            HexFeatures.RESTLESS to "restless",
            HexFeatures.WATER_DISTANCE to "water distance",
            HexFeatures.DAYS_DRY to "days dry",
            HexFeatures.PULL to "pull",
            HexFeatures.MOON to "moon",
            HexFeatures.TO_BIRTHS_SIN to "to births sin",
            HexFeatures.TO_BIRTHS_COS to "to births cos",
            HexFeatures.REMEMBERED to "remembered",
            HexFeatures.REMEMBERED_SAME_SEASON to "remembered same season",
            HexFeatures.REMEMBERED_FED to "remembered fed",
            HexFeatures.REMEMBERED_WATER to "remembered water",
            HexFeatures.REMEMBERED_DANGER to "remembered danger",
            HexFeatures.REMEMBERED_AGE to "remembered age",
            HexFeatures.ANCESTORS to "ancestors",
            HexFeatures.APPOINTMENT to "appointment",
            HexFeatures.HOUSES to "houses",
            HexFeatures.OWN_SIZE to "own size",
            HexFeatures.LASTING_WATER to "lasting water",
            HexFeatures.SICKNESS to "sickness",
            HexFeatures.TREES to "trees",
            HexFeatures.SHRUBS to "shrubs",
            HexFeatures.GRASS to "grass",
            HexFeatures.MARSH to "marsh",
            HexFeatures.WATER_AGAINST_USUAL to "water against usual",
            HexFeatures.PATHS to "paths",
            HexFeatures.HERDS_ABOUT to "herds about",
            HexFeatures.DUNG to "dung",
            HexFeatures.GRAZING_AND_KILLS to "grazing and kills",
            HexFeatures.KNOWN_GROUND to "known ground",
            HexFeatures.BEYOND_OWN_GROUND to "beyond own ground",
            HexFeatures.OTHERS_MARKS to "others' marks",
            HexFeatures.FISH to "fish and shellfish",
            HexFeatures.KEEPS_HEADING to "keeps heading",
            HexFeatures.ON_ROUND to "on round",
            HexFeatures.FORAGE_QUALITY to "forage quality",
            HexFeatures.KILL_FEAR to "kill fear",
            HexFeatures.BURNING to "burning",
            HexFeatures.SNOW_AHEAD to "snow ahead",
            HexFeatures.STRONGER_HUNTER to "stronger hunter",
            HexFeatures.REMEMBERED_LACKED to "remembered lacked",
            HexFeatures.TOWARD_LACKED to "toward lacked",
            HexFeatures.USUAL_AHEAD to "usual temperature, month 1 ahead",
            HexFeatures.USUAL_AHEAD + 1 to "usual temperature, month 2 ahead",
            HexFeatures.USUAL_AHEAD + 2 to "usual temperature, month 3 ahead"
        )
        assertEquals(HexFeatures.SIZE, named.size)
        for ((slot, name) in named) assertEquals(name, Schema.PLACE[slot], "feature slot $slot")
    }

    /** The bands' named ways sit in their outputs where the saved names say, and the lists of names fit the numbers the bands read and write. */
    @Test
    fun theBandsWaysAreWhereTheSavedNamesSayTheyAre() {
        assertEquals(WAYS_IN, Schema.WAYS_IN.size)
        assertEquals(WAYS_OUT, Schema.WAYS_OUT.size)
        for (w in BandWay.entries) assertEquals(w.label, Schema.WAYS_OUT[w.slot], w.name)
        assertEquals("scouts", Schema.WAYS_OUT[FOOD_TYPES + 1 + Tool.entries.size])
        assertEquals("build: mudbrick", Schema.WAYS_OUT.last())
    }

    /** A band's effort slots are every kind of food before grain (it goes by grass seed's). */
    @Test
    fun theEffortSlotsAreTheFoodsBeforeGrain() {
        assertEquals(listOf(Food.GRAIN), Food.entries.drop(FOOD_TYPES))
        assertEquals(Food.GRAIN.ordinal, FOOD_TYPES)
    }
}

class NetVersionTest {
    @Test
    fun aSavedNetCarriesItsRolesVersionAndOneWithoutIsNone() {
        val dir = java.nio.file.Files.createTempDirectory("netversion").toFile()
        val net = Mlp.random(intArrayOf(2, 3, 1), Random(1))
        val f = java.io.File(dir, "a.mlp"); val g = java.io.File(dir, "b.mlp")
        Schema.save(net, f, listOf("a", "b"), listOf("o"), Role.PEOPLE.net)
        Schema.save(net, g, listOf("a", "b"), listOf("o"))
        assertEquals(Role.PEOPLE.net, Schema.versionOf(f))
        assertEquals(NetVersion.NONE, Schema.versionOf(g))
        assertEquals(NetVersion.NONE, Schema.versionOf(java.io.File(dir, "missing.mlp")))
        assertEquals(listOf("a", "b"), Schema.load(f, listOf("a", "b"), listOf("o")).let { listOf("a", "b") })
        dir.deleteRecursively()
    }

    @Test
    fun aRoleWhoseKeptNetsWereSavedUnderAnotherMajorIsToldApart() {
        val dir = java.nio.file.Files.createTempDirectory("netmajor").toFile()
        val net = Mlp.random(intArrayOf(2, 3, 1), Random(1))
        val f = Role.HUNTERS.file(dir)
        Schema.save(net, f, listOf("a", "b"), listOf("o"), Role.HUNTERS.net)
        assertEquals(false, Role.HUNTERS.netMajorChanged(dir))
        Schema.save(net, f, listOf("a", "b"), listOf("o"), NetVersion(Role.HUNTERS.net.major - 1, 0))
        assertEquals(true, Role.HUNTERS.netMajorChanged(dir))
        Schema.save(net, f, listOf("a", "b"), listOf("o"))
        assertEquals(true, Role.HUNTERS.netMajorChanged(dir))
        assertEquals(false, Role.PEOPLE.netMajorChanged(dir))
        dir.deleteRecursively()
    }
}
