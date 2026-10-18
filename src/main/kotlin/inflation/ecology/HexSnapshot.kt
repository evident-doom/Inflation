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
     * 1.0: onager live both ways (#179).
     */
    const val MODEL_MAJOR = 1
    const val MODEL_MINOR = 0
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
