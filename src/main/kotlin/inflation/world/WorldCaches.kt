package inflation.world

import inflation.Config
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

/** The caches' own classes, which play no part in making a world and so are left out of their keys. */
private fun isCache(simpleClassName: String) = simpleClassName.startsWith("WorldCache") || simpleClassName.startsWith("LandformCache") || simpleClassName.startsWith("WorldCaches")

/**
 * A generated world kept on disk, so a run need not generate it again: [load] gives it back if
 * one for [cfg] was saved by the same world-generating code, else generates it with [log] and
 * keeps it. worldCache=false in the config turns this off.
 */
object WorldCache {
    private const val WORLD_MAGIC = 0x48455857 // "HEXW"
    val dir = File(System.getProperty("user.home"), ".inflation/world-cache")

    fun load(cfg: WorldConfig, log: (String) -> Unit = {}): World {
        if (!Config.flag("worldCache", true)) return World.generate(cfg, log = log)
        val f = File(dir, "world-%016x.bin".format(key(cfg)))
        if (f.exists()) runCatching {
            DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
                require(inp.readInt() == WORLD_MAGIC)
                log("loading the world kept in ${f.name}")
                val w = Restorer().make(Reader(inp).node(), null, Any::class.java) as World
                return w
            }
        }.onFailure { System.err.println("could not load the kept world ${f.name}: ${it.message}; generating it again") }
        // (The land and its rocks, the slow part, kept apart: changing the climate makes only the rest again.)
        val w = World.generate(cfg, LandformCache.load(cfg, log), log)
        runCatching {
            dir.mkdirs()
            val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
                out.writeInt(WORLD_MAGIC)
                Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true).value(w)
            }
            if (!tmp.renameTo(f)) tmp.delete()
        }.onFailure { System.err.println("could not keep the world: ${it.message}") }
        return w
    }

    /** The settings, and the compiled world-generating code: any change to either makes a new world. */
    fun key(cfg: WorldConfig): Long {
        val crc = java.util.zip.CRC32()
        crc.update(cfg.toString().toByteArray())
        val loader = WorldCache::class.java.classLoader
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
 * The land before the climate ([World.landform]: plates, volcanoes, rocks, erosion
 * and sediment) kept on disk, so that a world made again after a change to the climate, the
 * rivers or the soils skips the erosion, which takes most of the time. Kept by the settings and the
 * landform-making code only (tectonics, volcanoes, geology, erosion, hydrology, noise, the grid and
 * the world's own making). landformCache=false in the config turns this off.
 */
object LandformCache {
    private const val MAGIC = 0x4845584C // "HEXL"
    val dir = File(System.getProperty("user.home"), ".inflation/landform-cache")
    private val PARTS = listOf("Tectonics", "Volcan", "Geology", "Erosion", "Hydrology", "Drainage", "Noise", "Grid", "FloatHeap", "Rock", "World", "Landform", "Plate")

    fun load(cfg: WorldConfig, log: (String) -> Unit = {}): Landform {
        if (!Config.flag("landformCache", true)) return World.landform(cfg, log)
        val f = File(dir, "landform-%016x.bin".format(key(cfg)))
        if (f.exists()) runCatching {
            DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC)
                log("loading the land and its rocks kept in ${f.name}")
                return Restorer().make(Reader(inp).node(), null, Any::class.java) as Landform
            }
        }.onFailure { System.err.println("could not load the kept land ${f.name}: ${it.message}; making it again") }
        val land = World.landform(cfg, log)
        runCatching {
            dir.mkdirs()
            val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true).value(land)
            }
            if (!tmp.renameTo(f)) tmp.delete()
        }.onFailure { System.err.println("could not keep the land: ${it.message}") }
        return land
    }

    private fun key(cfg: WorldConfig): Long {
        val crc = java.util.zip.CRC32()
        crc.update(cfg.toString().toByteArray())
        val loader = WorldCache::class.java.classLoader
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
