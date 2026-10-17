package inflation.world

import inflation.store.Writer
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/** Makes a small world (or only its land) and keeps it as the caches do: what [WorldCacheTest] watches being loaded. */
object WorldMaking {
    private val cfg = WorldConfig(seed = 3, widthKm = 200, heightKm = 200, erosionSteps = 5, plates = 5, oceanMarginKm = 40.0)

    private fun keep(made: Any) {
        val out = DataOutputStream(OutputStream.nullOutputStream())
        Writer(out, { c -> c.name.startsWith("inflation.") && !c.isSynthetic && !c.name.contains("\$\$Lambda") }, lazies = true).value(made)
    }

    @JvmStatic fun world() = keep(World.generate(cfg, World.landform(cfg)))
    @JvmStatic fun landform() = keep(World.landform(cfg))
}

/**
 * The caches' keys hash only the world-package classes that make a world, picked by name. A class that
 * makes a world but is left out of the key would have cached worlds outlive changes to it, so every
 * world-package class that making and keeping a world loads must count in its cache's key.
 */
class WorldCacheTest {
    /** The world-package classes (simple names) that [WorldMaking]'s [method] loads, in a class loader of its own (the caches themselves aside). */
    private fun loadedBy(method: String): Set<String> {
        val loaded = sortedSetOf<String>()
        val urls = listOf(World::class.java, WorldMaking::class.java).map { it.protectionDomain.codeSource.location }.distinct().toTypedArray()
        val loader = object : URLClassLoader(urls, WorldCacheTest::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("inflation.")) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val c = findLoadedClass(name) ?: findClass(name)
                    val simple = name.substringAfterLast('.')
                    if (name == "inflation.world.$simple" && listOf("WorldMaking", "WorldCache", "LandformCache").none { simple.startsWith(it) }) loaded += simple
                    return c
                }
            }
        }
        loader.use { it.loadClass(WorldMaking::class.java.name).getMethod(method).invoke(null) }
        return loaded
    }

    @Test
    fun `making and keeping a world loads only classes that count in its key`() {
        val outside = loadedBy("world").filter { !WorldCache.counts(it) }
        assertTrue(outside.isEmpty(), "classes making a world but left out of WorldCache's key: $outside")
    }

    @Test
    fun `making and keeping the land loads only classes that count in its key`() {
        val outside = loadedBy("landform").filter { !LandformCache.counts(it) }
        assertTrue(outside.isEmpty(), "classes making the land but left out of LandformCache's key: $outside")
    }
}
