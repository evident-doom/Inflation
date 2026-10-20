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

/**
 * The world-package classes that play no part in making a world, left out of the caches' keys so that
 * changing them does not make every world again: the caches themselves, the hex model run on the
 * world (Hex*, Weather* and its ClimateEvent, Sunlight, Eruption*), and the fine reference models only
 * hex-land-check runs (LiveWater with its works, Vegetation and its LifeForm). WorldCacheTest checks that
 * making a world and keeping it touches none of them.
 */
private fun makesNoWorld(simpleClassName: String) = listOf(
    "WorldCache", "LandformCache", "Hex", "Weather", "ClimateEvent", "Sunlight", "Eruption", "LiveWater", "Dam", "Diversion", "Irrigation", "Vegetation",
    "LifeForm",
).any { simpleClassName.startsWith(it) }

/**
 * A key for [cfg] and the compiled world-package classes [counts] picks (by simple name): any change to
 * either gives a new key.
 */
private fun codeKey(cfg: WorldConfig, counts: (String) -> Boolean): Long {
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
        if (!counts(n.substringAfterLast('/'))) continue
        crc.update(n.toByteArray()); crc.update(bytes())
    }
    return crc.value xor (cfg.seed shl 32)
}

/**
 * [what] kept on disk in [file] under [magic]: read back if it is there and readable, else made with
 * [make] and kept. [name] says what it is in the log and in errors.
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> kept(file: File, magic: Int, name: String, log: (String) -> Unit, make: () -> T): T {
    if (file.exists()) runCatching {
        DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
            require(inp.readInt() == magic)
            log("loading the $name kept in ${file.name}")
            return Restorer().make(Reader(inp).node(), null, Any::class.java) as T
        }
    }.onFailure { System.err.println("could not load the kept $name ${file.name}: ${it.message}; making it again") }
    val made = make()
    runCatching {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
            out.writeInt(magic)
            Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true).value(made)
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }.onFailure { System.err.println("could not keep the $name: ${it.message}") }
    return made
}

/**
 * A generated world kept on disk, so a run need not generate it again: [load] gives it back if
 * one for [cfg] was saved by the same world-generating code, else generates it with [log] and
 * keeps it. worldCache=false in the config turns this off.
 */
object WorldCache {
    private const val WORLD_MAGIC = 0x48455857 // "HEXW"
    val dir = File(System.getProperty("user.home"), ".inflation/world-cache")

    fun load(cfg: WorldConfig, log: (String) -> Unit = {}): World {
        if (!Config.flag(Config.Key.WORLD_CACHE, true)) return World.generate(cfg, log = log)
        // (The land and its rocks, the slow part, kept apart: changing the climate makes only the rest again.)
        return kept(File(dir, "world-%016x.bin".format(key(cfg))), WORLD_MAGIC, "world", log) { World.generate(cfg, LandformCache.load(cfg, log), log) }
    }

    /** The settings, and the compiled world-generating code: any change to either makes a new world. */
    fun key(cfg: WorldConfig): Long = codeKey(cfg, ::counts)

    /** Whether the world-package class [simpleClassName] counts in the key. */
    internal fun counts(simpleClassName: String) = !makesNoWorld(simpleClassName)
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
        if (!Config.flag(Config.Key.LANDFORM_CACHE, true)) return World.landform(cfg, log)
        return kept(File(dir, "landform-%016x.bin".format(key(cfg))), MAGIC, "land and its rocks", log) { World.landform(cfg, log) }
    }

    private fun key(cfg: WorldConfig): Long = codeKey(cfg, ::counts)

    /** Whether the world-package class [simpleClassName] counts in the key. */
    internal fun counts(simpleClassName: String) = !makesNoWorld(simpleClassName) && PARTS.any { simpleClassName.startsWith(it) }
}
