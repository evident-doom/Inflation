package inflation.ecology

import inflation.Config
import inflation.store.Named
import inflation.store.Reader
import inflation.store.Restorer
import inflation.store.Writer

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
 * It goes by field names, not by layout: a field the code no longer has is dropped, a field the
 * saved state lacks keeps what the fresh model gave it, an array that has grown (a kind added at its
 * end) keeps the fresh model's values past the saved ones, and an array that has shrunk is taken as saved. So small changes to the code still load; large ones (a different map) are refused.
 */
object HexSnapshot {
    private const val MAGIC = 0x48455853 // "HEXS"
    /** 2: what lazy fields hold is saved. Version 1 saves still load; code from before version 2 refuses version 2 saves. */
    private const val VERSION = 2

    /** Writes the state of [sim] to [file]. */
    fun save(sim: HexSim, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeLong(sim.cfg.seed); out.writeInt(sim.land.n); out.writeLong(sim.weather.date.absoluteDay)
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
            require(version in 1..VERSION) { "$file was saved by another version ($version)" }
            val seed = inp.readLong(); val n = inp.readInt(); val day = inp.readLong()
            require(seed == sim.cfg.seed && n == sim.land.n) { "$file is of another world (seed $seed, $n places; this one seed ${sim.cfg.seed}, ${sim.land.n} places)" }
            val r = Reader(inp, SPECIES)
            val byName = roots(sim).toMap()
            val restore = Restorer()
            repeat(byName.size) {
                val name = inp.readUTF()
                val node = r.node()
                byName[name]?.let { restore.into(it, node) }
            }
            return day
        }
    }

    /**
     * A generated world kept on disk, so a run need not generate it again: [load] gives it back if
     * one for [cfg] was saved by the same world-generating code, else generates it with [log] and
     * keeps it. worldCache=false in the config turns this off.
     */
    object WorldCache {
        private const val WORLD_MAGIC = 0x48455857 // "HEXW"
        val dir = File(System.getProperty("user.home"), ".inflation/world-cache")

        fun load(cfg: inflation.world.WorldConfig, log: (String) -> Unit = {}): inflation.world.World {
            if (!Config.flag("worldCache", true)) return inflation.world.World.generate(cfg, log = log)
            val f = File(dir, "world-%016x.bin".format(key(cfg)))
            if (f.exists()) runCatching {
                DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
                    require(inp.readInt() == WORLD_MAGIC)
                    log("loading the world kept in ${f.name}")
                    val w = Restorer().make(Reader(inp, SPECIES).node(), null, Any::class.java) as inflation.world.World
                    return w
                }
            }.onFailure { System.err.println("could not load the kept world ${f.name}: ${it.message}; generating it again") }
            // (The land and its rocks, the slow part, kept apart: changing the climate makes only the rest again.)
            val w = inflation.world.World.generate(cfg, LandformCache.load(cfg, log), log)
            runCatching {
                dir.mkdirs()
                val tmp = File(f.path + ".tmp")
                DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
                    out.writeInt(WORLD_MAGIC)
                    Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true, SPECIES).value(w)
                }
                if (!tmp.renameTo(f)) tmp.delete()
            }.onFailure { System.err.println("could not keep the world: ${it.message}") }
            return w
        }

        /** The settings, and the compiled world-generating code: any change to either makes a new world. */
        fun key(cfg: inflation.world.WorldConfig): Long {
            val crc = java.util.zip.CRC32()
            crc.update(cfg.toString().toByteArray())
            val loader = HexSnapshot::class.java.classLoader
            val url = loader.getResource("inflation/world/World.class") ?: return crc.value
            val names = ArrayList<Pair<String, () -> ByteArray>>()
            if (url.protocol == "jar") {
                val jar = (url.openConnection() as java.net.JarURLConnection).jarFile
                for (e in jar.entries()) if (e.name.startsWith("inflation/world/") && e.name.endsWith(".class")) names += e.name to { jar.getInputStream(e).readBytes() }
            } else {
                val d = File(url.toURI()).parentFile
                for (c in d.listFiles().orEmpty()) if (c.name.endsWith(".class")) names += ("inflation/world/" + c.name) to { c.readBytes() }
            }
            // The hex model's own classes live in the same package but play no part in making the world.
            for ((n, bytes) in names.sortedBy { it.first }) {
                val simple = n.substringAfterLast('/')
                if (simple.startsWith("Hex") || simple.startsWith("Weather") || simple.startsWith("LiveWater") || simple.startsWith("Eruption")) continue
                crc.update(n.toByteArray()); crc.update(bytes())
            }
            return crc.value xor (cfg.seed shl 32)
        }
    }

    /**
     * The land before the climate ([inflation.world.World.landform]: plates, volcanoes, rocks, erosion
     * and sediment) kept on disk, so that a world made again after a change to the climate, the
     * rivers or the soils skips the erosion, which takes most of the time. Kept by the settings and the
     * landform-making code only (tectonics, volcanoes, geology, erosion, hydrology, noise, the grid and
     * the world's own making). landformCache=false in the config turns this off.
     */
    object LandformCache {
        private const val MAGIC = 0x4845584C // "HEXL"
        val dir = File(System.getProperty("user.home"), ".inflation/landform-cache")
        private val PARTS = listOf("Tectonics", "Volcan", "Geology", "Erosion", "Hydrology", "Drainage", "Noise", "Grid", "FloatHeap", "Rock", "World", "Landform", "Plate")

        fun load(cfg: inflation.world.WorldConfig, log: (String) -> Unit = {}): inflation.world.Landform {
            if (!Config.flag("landformCache", true)) return inflation.world.World.landform(cfg, log)
            val f = File(dir, "landform-%016x.bin".format(key(cfg)))
            if (f.exists()) runCatching {
                DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
                    require(inp.readInt() == MAGIC)
                    log("loading the land and its rocks kept in ${f.name}")
                    return Restorer().make(Reader(inp, SPECIES).node(), null, Any::class.java) as inflation.world.Landform
                }
            }.onFailure { System.err.println("could not load the kept land ${f.name}: ${it.message}; making it again") }
            val land = inflation.world.World.landform(cfg, log)
            runCatching {
                dir.mkdirs()
                val tmp = File(f.path + ".tmp")
                DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
                    out.writeInt(MAGIC)
                    Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true, SPECIES).value(land)
                }
                if (!tmp.renameTo(f)) tmp.delete()
            }.onFailure { System.err.println("could not keep the land: ${it.message}") }
            return land
        }

        private fun key(cfg: inflation.world.WorldConfig): Long {
            val crc = java.util.zip.CRC32()
            crc.update(cfg.toString().toByteArray())
            val loader = HexSnapshot::class.java.classLoader
            val url = loader.getResource("inflation/world/World.class") ?: return crc.value
            val names = ArrayList<Pair<String, () -> ByteArray>>()
            if (url.protocol == "jar") {
                val jar = (url.openConnection() as java.net.JarURLConnection).jarFile
                for (e in jar.entries()) if (e.name.startsWith("inflation/world/") && e.name.endsWith(".class")) names += e.name to { jar.getInputStream(e).readBytes() }
            } else {
                val d = File(url.toURI()).parentFile
                for (c in d.listFiles().orEmpty()) if (c.name.endsWith(".class")) names += ("inflation/world/" + c.name) to { c.readBytes() }
            }
            for ((n, bytes) in names.sortedBy { it.first }) {
                val simple = n.substringAfterLast('/')
                if (PARTS.none { simple.startsWith(it) }) continue
                crc.update(n.toByteArray()); crc.update(bytes())
            }
            return crc.value xor (cfg.seed shl 32)
        }
    }

    /** Kinds are saved by name: the code defines them. */
    private val SPECIES = Named(Species::class.java, { it.name }, { n -> Catalogue.all.firstOrNull { it.name == n } })

    private fun roots(sim: HexSim) = listOf("weather" to sim.weather, "land" to sim.land, "fauna" to sim.fauna, "groups" to sim.groups, "eruptions" to sim.eruptions)

    // ---- what is saved

    /** The world's fixed structure, regenerated from the seed; and what is only worked out from the rest. */
    private val FIXED = setOf("World", "HexMap", "HexWorld", "Grid", "WorldConfig", "Climate", "Terrain", "Geology", "Hydrology", "Rivers", "Profile")

    private fun saved(c: Class<*>): Boolean {
        val n = c.name
        if (c.isSynthetic || n.contains("\$\$Lambda") || n.contains("\$Lambda")) return false
        if (c.simpleName in FIXED) return false
        if (HexPolicy::class.java.isAssignableFrom(c)) return false
        return n.startsWith("inflation.") || n.startsWith("kotlin.random.")
    }
}
