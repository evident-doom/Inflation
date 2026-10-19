package inflation.ecology

import inflation.store.Named
import inflation.store.Reader
import inflation.store.Restorer
import inflation.store.Writer
import inflation.world.Biome
import inflation.world.Climate
import inflation.world.Geology
import inflation.world.Grid
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Terrain
import inflation.world.World
import inflation.world.WorldConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The state of a running hex model saved to a file and loaded back into a freshly built one, so that
 * a long run can be carried on after small fixes to the code instead of started anew.
 *
 * What is saved is everything that changes as the model runs: the weather, the land and its plants
 * and water, the residents, small life and water life, the groups, the eruptions. The world itself
 * (terrain, rivers, climate normals, the hexes) is not saved: it is generated again from the seed.
 *
 * Fields marked @Transient are not saved: what the model only works out from the world (each place's
 * slope, soils and normals, the hex model's rivers, the water's depths), so that a fix to how it is worked
 * out reaches a run carried on from a save too.
 *
 * It goes by field names, not by layout: a field the code no longer has is dropped, a field the
 * saved state lacks keeps what the fresh model gave it, an array that has grown keeps the fresh model's values past the saved
 * ones, and an array that has shrunk is taken as saved. So small changes to the code still load; a save whose numbers would
 * mean something else to today's code is refused: another map, another model major version ([MODEL_MAJOR]), or other kinds
 * kept as residents or as groups.
 */
object HexSnapshot {
    private const val MAGIC = 0x48455853 // "HEXS"
    /**
     * The layout of the file. 2: what lazy fields hold is saved. 3: the order of [ORDERED]'s entries is saved. 4: the model
     * version and the kinds kept by kind are saved; older saves are refused, as nothing says what model made them. Older code
     * refuses newer saves.
     */
    private const val VERSION = 4

    /**
     * What saved state means. Raise the major version with a change after which a saved world's numbers would mean
     * something else or leave the model in a state it could not reach by running (a kind living another way, a stock
     * measured in other units, a table kept by another key): a save of another major version is refused, and the test
     * worlds must be settled again. Raise the minor version with a change that leaves saved state valid but sets the model
     * to drift from it (a growth rate retuned, a new cost): such a save loads with a warning, and whoever tests on it
     * judges whether to settle again first. Set the minor version back to 0 with a new major one.
     *
     * 1.0: onager live both ways.
     * 1.1: sowing charged its full person-days.
     * 1.2: gathering materials takes time.
     * 1.3: caching food takes time.
     * 1.4: mending first is a band way.
     * 1.5: residents count the protein in straw and in what they switch to, by weight, as herds do.
     * 1.6: residents remember days without water.
     * 1.7: bears sleep through the cold only when hungry.
     * 1.8: thirsty residents spread toward water (#210).
     * 1.9: crowding and breeding room count a kind's animals in both forms (#211).
     * 1.10: herds sicken by crowding as residents do, counted over the ground each herd grazes.
     * 1.11: fallen mast lies as winter food, the mast and root stocks from records; rooting churns the ground at its
     * recorded rate; mothers bear as their food and reserves can build their young.
     * 1.12: resident hunters take game the less readily the bigger it is past their preferred size; jackals prefer 4 kg, take 15 at most.
     * 1.15: herds count the animals hunters would kill as deaths where they choose to go, and no longer shun hunters for food.
     * 2.0: every plant-eater lives both ways: residents leave as groups where staying is clearly deadlier than a place a
     * month away, and groups settle where it is not; resident hunters, disease and crowding meet a non-migratory herd as
     * its kind's groups over HERD_UNIT times the ground.
     * 2.1: species records follow their sources (#213); a leaving group heads for the place found for it; groups form only
     * where no slope leads to safety; residents walk towards forage within a week's going (#219).
     * 2.2: residents weigh places by the hunters' kills too, resident and roaming.
     * 2.3: grass loses nourishment and protein as it goes to stem and seed (HexPlants.grassAge).
     * 2.4: crowding halves a place's appeal for residents where disease starts to bite (CROWDED), not at 20 times usual.
     * 2.5: afternoon storms over land against the observed free air: summer rain on high ground north of about 36 °N.
     * 2.6: animals eat soft lying snow for water (HexDrinkingWater.snowWater); rain on snow ices it over until fresh snow.
     * 2.7: the summer high reaches north only to about 46 °N (fronts more often in the north's summer), and air over hot
     * inland ground holds more before it rains out on slopes.
     * 2.8: residents move every second week (Tuning.residentMoveWeeks), and their animals under 1% of usual numbers at a
     * place gather into the next thin place (Tuning.residentFloor).
     * 2.9: hooves no longer take grown shrubs; they kill the seedlings of shrubs and trees on the ground they step on (HexPlants.tread).
     * 2.10: a place's room for an arriving band is how well it would be fed there by the residents' own feeding rule
     * ([HexFauna.forage]), so the foresight behind walking, band shifts and gathering into herds no longer foresees
     * starvation that those already there don't meet.
     * 2.11: the grass a plant-eater gets at under snow falls evenly from bare ground to none at its snow limit
     * ([grassUnderSnow]), in its feeding and in how it weighs a place; the wild horse's limit is 125 mm of water.
     * 2.12: residents set off as a herd, and herds settle back, by the death odds a herd weighs its places by
     * ([HexGroups.herdOddsAt]); a herd no longer keeps together until it is beyond [gatherKm] of where it formed; and the
     * place residents set off for, and a herd weighs settling against, is one it can walk to (not across the sea or a
     * river too deep to cross).
     * 2.13: a herd weighing whether to settle back weighs itself, whole, at both places, its own heads where it grazes now
     * taken out of the others' (it was crowded by itself where it stood and weighed as a band's worth alone elsewhere).
     */
    const val MODEL_MAJOR = 2
    const val MODEL_MINOR = 13
    /**
     * Enums whose entries index saved arrays by position (a band's skills, tools, goods, stores, illnesses, customs, plant
     * lore, ways): a save is refused where the code has them in another order or without one it saved, since the
     * numbers would land on the wrong entries; entries added at the end keep the fresh model's values.
     */
    internal val ORDERED: List<Class<out Enum<*>>> = listOf(Skill::class.java, Tool::class.java, Material::class.java, Store::class.java,
        Illness::class.java, Tradition::class.java, Biome::class.java, BandWay::class.java)

    /** Writes the state of [sim] to [file]. */
    fun save(sim: HexSim, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } },
            1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeLong(sim.cfg.seed); out.writeInt(sim.land.n); out.writeLong(sim.weather.date.absoluteDay)
            out.writeInt(ORDERED.size)
            for (e in ORDERED) { out.writeUTF(e.name); out.writeInt(e.enumConstants.size); for (c in e.enumConstants) out.writeUTF(c.name) }
            out.writeInt(MODEL_MAJOR); out.writeInt(MODEL_MINOR)
            val rs = rosters(sim)
            out.writeInt(rs.size)
            for ((name, kinds) in rs) { out.writeUTF(name); out.writeInt(kinds.size); for (k in kinds) out.writeUTF(k) }
            val w = Writer(out, ::saved, lazies = false, SPECIES)
            for ((name, root) in roots(sim)) { out.writeUTF(name); w.value(root) }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) error("could not write $file")
    }

    /** Loads the state in [file] into [sim], which must be built over the same world (seed and size). Returns the day it was saved on. */
    fun load(sim: HexSim, file: File): Long {
        DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
            require(inp.readInt() == MAGIC) { "$file is not a saved hex world" }
            val version = inp.readInt()
            require(version <= VERSION) { "$file was saved by newer code (file version $version)" }
            require(version == VERSION) { "$file was saved before saves carried the model version that made them, so it may hold numbers today's model reads otherwise: settle it again" }
            val seed = inp.readLong(); val n = inp.readInt(); val day = inp.readLong()
            require(seed == sim.cfg.seed && n == sim.land.n) {
                "$file is of another world (seed $seed, $n places; this one seed ${sim.cfg.seed}, ${sim.land.n} places)"
            }
            repeat(inp.readInt()) {
                val name = inp.readUTF(); val saved = List(inp.readInt()) { inp.readUTF() }
                orderProblem(name, saved)?.let { throw IllegalArgumentException("$file: $it") }
            }
            val major = inp.readInt(); val minor = inp.readInt()
            modelProblem(major)?.let { throw IllegalArgumentException("$file: $it") }
            val code = rosters(sim).toMap()
            repeat(inp.readInt()) {
                val name = inp.readUTF(); val saved = List(inp.readInt()) { inp.readUTF() }
                rosterProblem(name, saved, code[name].orEmpty())?.let { throw IllegalArgumentException("$file: $it") }
            }
            if (minor != MODEL_MINOR) println("WARNING: $file was saved by model $major.$minor, this is $MODEL_MAJOR.$MODEL_MINOR: it loads, but the model will drift from it; settle it again if that matters to the test.")
            val r = Reader(inp, SPECIES)
            val byName = roots(sim).toMap()
            val restore = Restorer()
            repeat(byName.size) {
                val name = inp.readUTF()
                val node = r.node()
                byName[name]?.let { restore.into(it, node) }
            }
            if (restore.dropped.isNotEmpty()) System.err.println("$file: the code no longer has, and so dropped: ${restore.dropped.joinToString(", ")}")
            return day
        }
    }

    /**
     * Why entries [saved] in this order for the enum class [name] would load wrongly (null if they load right): the code
     * must have them in the same order, perhaps with more after.
     */
    internal fun orderProblem(name: String, saved: List<String>): String? {
        val code = ORDERED.firstOrNull { it.name == name }?.enumConstants?.map { it.name } ?: return null
        if (code.size >= saved.size && code.subList(0, saved.size) == saved) return null
        return "${name.substringAfterLast('.')} is saved in another order than the code has, so its values would land on the wrong entries: saved $saved, code $code"
    }

    /** Why a save made by model major version [major] would load wrongly (null if it loads). */
    internal fun modelProblem(major: Int): String? =
        if (major == MODEL_MAJOR) null else "it was saved by model major version $major, this is $MODEL_MAJOR: its numbers mean something else to today's model; settle it again"

    /**
     * Why the kinds [saved] for the tables kept by kind under [name] would load wrongly against the code's [code] (null if
     * they load right): the tables are restored by position, and a kind added, dropped or moved would start from numbers that
     * are not its own.
     */
    internal fun rosterProblem(name: String, saved: List<String>, code: List<String>): String? =
        if (saved == code) null else "it keeps other kinds as $name than the code does, so their numbers would land on the wrong kinds or start from none: saved $saved, code $code"

    /**
     * The kinds each part keeps its tables by, in order: the residents and those living as groups. Left out are the kinds the
     * config moves for a comparison after a load (people=false), which change the code's lists but not what a
     * save holds.
     */
    internal fun rosters(sim: HexSim): List<Pair<String, List<String>>> {
        fun names(kinds: List<Species>) = kinds.filter { !it.human }.map { it.name }
        return listOf("residents" to names(sim.fauna.kinds), "groups" to names(sim.groups.kinds))
    }

    /** Kinds are saved by name: the code defines them. */
    private val SPECIES = Named(Species::class.java, { it.name }, { n -> Catalogue.all.firstOrNull { it.name == n } })

    internal fun roots(sim: HexSim) = listOf("weather" to sim.weather, "land" to sim.land, "fauna" to sim.fauna, "groups" to sim.groups,
        "eruptions" to sim.eruptions)

    // ---- what is saved

    /** The world's fixed structure, regenerated from the seed; and what is only worked out from the rest. */
    private val FIXED = setOf(World::class.java, HexMap::class.java, HexWorld::class.java, Grid::class.java, WorldConfig::class.java,
        Climate::class.java, Terrain::class.java, Geology::class.java, Profile::class.java)

    internal fun saved(c: Class<*>): Boolean {
        val n = c.name
        if (c.isSynthetic || n.contains("\$\$Lambda") || n.contains("\$Lambda")) return false
        if (c in FIXED) return false
        if (HexPolicy::class.java.isAssignableFrom(c)) return false
        return n.startsWith("inflation.") || n.startsWith("kotlin.random.")
    }
}
