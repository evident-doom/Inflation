package inflation.ecology

import inflation.learning.Mlp
import inflation.learning.NetWays
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class HexSnapshotTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    private fun bytes(sim: HexSim): ByteArray {
        val f = File.createTempFile("hexsnap", ".hexs")
        try {
            HexSnapshot.save(sim, f)
            return GZIPInputStream(f.inputStream()).use { it.readBytes() }
        } finally { f.delete() }
    }

    @Test
    fun `a world saved and loaded carries on as if it had never stopped`() {
        val world = World.generate(cfg)
        val a = HexSim(world)
        repeat(30) { a.step() }
        val f = File.createTempFile("hexsnap", ".hexs")
        try {
            HexSnapshot.save(a, f)
            repeat(30) { a.step() }
            val b = HexSim(World.generate(cfg))
            HexSnapshot.load(b, f)
            assertEquals(emptyList(), b.groups.groups.filter { it.alive }.flatMap { it.violations() })
            repeat(30) { b.step() }
            val x = bytes(a); val y = bytes(b)
            val diff = (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] } ?: if (x.size == y.size) -1 else minOf(x.size, y.size)
            if (diff >= 0) fail("the loaded world's state differs from the one that ran on, from byte $diff of ${x.size}, in:\n" +
                StateDiff().of(a, b).joinToString("\n"))
        } finally { f.delete() }
    }

    @Test
    fun `a save with enum entries in another order is refused, one with fewer at the end is not`() {
        val skills = Skill.entries.map { it.name }
        assertEquals(null, HexSnapshot.orderProblem(Skill::class.java.name, skills))
        assertEquals(null, HexSnapshot.orderProblem(Skill::class.java.name, skills.dropLast(1)))
        assertNotNull(HexSnapshot.orderProblem(Skill::class.java.name, skills.reversed()))
        assertNotNull(HexSnapshot.orderProblem(Skill::class.java.name, skills + "GONE"))
    }

    @Test
    fun `a save of another model major version or with other kinds by kind is refused`() {
        assertEquals(null, HexSnapshot.modelProblem(HexSnapshot.MODEL_MAJOR))
        assertNotNull(HexSnapshot.modelProblem(HexSnapshot.MODEL_MAJOR - 1))
        val kinds = listOf("roe deer", "red deer")
        assertEquals(null, HexSnapshot.rosterProblem("residents", kinds, kinds))
        // (A kind added at the end too: its tables would start from nothing in a settled world.)
        assertNotNull(HexSnapshot.rosterProblem("residents", kinds, kinds + "onager"))
        assertNotNull(HexSnapshot.rosterProblem("residents", kinds, kinds.reversed()))
    }

    @Test
    fun `a saved world does not bring the learned ways it ran with`() {
        val world = World.generate(cfg)
        val a = HexSim(world)
        a.groups.ways = NetWays(Mlp.random(intArrayOf(WAYS_IN, 4, WAYS_OUT), kotlin.random.Random(1)))
        val f = File.createTempFile("hexsnap", ".hexs")
        try {
            HexSnapshot.save(a, f)
            val b = HexSim(world)
            HexSnapshot.load(b, f)
            assertEquals(null, b.groups.ways)
        } finally { f.delete() }
    }

    @Test
    fun `a year leaves no stock that is not a number, endless or below nothing`() {
        val sim = HexSim(World.generate(cfg))
        repeat(52) { sim.step() }
        val bad = ArrayList<String>()
        // Every number the model keeps per place, here and in the residents, small life and water.
        for ((name, o) in listOf("land" to sim.land, "land water" to sim.land.hydro, "drinking water" to sim.land.drink, "plants" to sim.land.plants,
            "fires" to sim.land.fire, "fauna" to sim.fauna, "small life" to sim.fauna.small, "water" to sim.fauna.water)) {
            var c: Class<*>? = o.javaClass
            while (c != null && c != Any::class.java) {
                for (fl in c.declaredFields) {
                    fl.isAccessible = true
                    val arrays = when (val v = fl.get(o)) { is FloatArray -> listOf(v); is Array<*> -> v.filterIsInstance<FloatArray>(); else -> emptyList() }
                    if (arrays.any { arr -> arr.any { !it.isFinite() } }) bad += "$name.${fl.name} not finite"
                }
                c = c.superclass
            }
        }
        val stocks = mapOf("herb" to sim.land.plants.herb, "shrub" to sim.land.plants.shrub, "tree" to sim.land.plants.tree, "soil" to sim.land.hydro.soil,
            "groundwater" to sim.land.hydro.groundwater,
            "rodents" to sim.fauna.small.rodents, "fish" to sim.fauna.water.fish) +
            sim.fauna.kinds.indices.flatMap { k ->
                listOf("${sim.fauna.kinds[k].name} adults" to sim.fauna.adults[k], "${sim.fauna.kinds[k].name} young" to sim.fauna.young[k])
            }
        for ((name, arr) in stocks) if (arr.any { it < 0f }) bad += "$name below nothing"
        for (g in sim.groups.groups) if (g.alive && (g.adults < 0 || g.young < 0 || g.fed.isNaN())) bad +=
            "group ${g.id} (${g.species.name}): ${g.adults} adults, ${g.young} young, fed ${g.fed}"
        assertEquals(emptyList(), bad)
    }
}

/**
 * The fields in which two models differ, walked by reflection over everything the model's own classes
 * save (what lazy fields hold too), so that a failed comparison names what was lost.
 */
private class StateDiff {
    private val out = ArrayList<String>()
    private val seen = IdentityHashMap<Any, Boolean>()

    fun of(a: Any, b: Any): List<String> { walk("sim", a, b); return out.map { it.substringBefore("[") }.distinct().take(30) }

    private fun walk(path: String, x: Any?, y: Any?) {
        if (x == null || y == null) { if ((x == null) != (y == null)) out += path; return }
        if (seen.put(x, true) != null) return
        when (x) {
            is FloatArray -> if (!x.contentEquals(y as FloatArray)) out += path
            is IntArray -> if (!x.contentEquals(y as IntArray)) out += path
            is DoubleArray -> if (!x.contentEquals(y as DoubleArray)) out += path
            is LongArray -> if (!x.contentEquals(y as LongArray)) out += path
            is ShortArray -> if (!x.contentEquals(y as ShortArray)) out += path
            is ByteArray -> if (!x.contentEquals(y as ByteArray)) out += path
            is BooleanArray -> if (!x.contentEquals(y as BooleanArray)) out += path
            is Array<*> -> if (x.size != (y as Array<*>).size) out += path else x.indices.forEach { walk("$path[$it]", x[it], y[it]) }
            is List<*> -> if (x.size != (y as List<*>).size) out += path else x.indices.forEach { walk("$path[$it]", x[it], y[it]) }
            is Set<*> -> if (x.size != (y as Set<*>).size) out += path else walk(path, x.toList(), y.toList())
            is Map<*, *> -> if (x.size != (y as Map<*, *>).size) out += path else walk(path, x.entries.map { listOf(it.key, it.value) },
                y.entries.map { listOf(it.key, it.value) })
            is Int, is Long, is Float, is Double, is Short, is Byte, is String, is Boolean, is Char, is Enum<*> -> if (x != y) out += path
            is Lazy<*> -> if (x.isInitialized() || (y as Lazy<*>).isInitialized()) walk(path, x.value, (y as Lazy<*>).value)
            else -> {
                val c = x.javaClass
                if (c.isSynthetic || c.name.contains("Lambda") || !c.name.startsWith("inflation.")) return
                var k: Class<*>? = c
                while (k != null && k != Any::class.java) {
                    for (f in k.declaredFields) if (!Modifier.isStatic(f.modifiers) && !Modifier.isTransient(f.modifiers)) {
                        f.isAccessible = true
                        walk("$path.${f.name}", f.get(x), f.get(y))
                    }
                    k = k.superclass
                }
            }
        }
    }
}
