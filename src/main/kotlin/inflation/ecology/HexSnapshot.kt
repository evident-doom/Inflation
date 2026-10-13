package inflation.ecology

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
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
 * saved state lacks keeps what the fresh model gave it, and an array whose length changed is taken
 * as saved. So small changes to the code still load; large ones (a different map) are refused.
 */
object HexSnapshot {
    private const val MAGIC = 0x48455853 // "HEXS"
    private const val VERSION = 1

    /** Writes the state of [sim] to [file]. */
    fun save(sim: HexSim, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }, 1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeLong(sim.cfg.seed); out.writeInt(sim.land.n); out.writeLong(sim.weather.date.absoluteDay)
            val w = Writer(out, ::saved, lazies = false)
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
            require(version == VERSION) { "$file was saved by another version ($version)" }
            val seed = inp.readLong(); val n = inp.readInt(); val day = inp.readLong()
            require(seed == sim.cfg.seed && n == sim.land.n) { "$file is of another world (seed $seed, $n places; this one seed ${sim.cfg.seed}, ${sim.land.n} places)" }
            val r = Reader(inp)
            val byName = roots(sim).toMap()
            val restore = Restorer()
            repeat(byName.size) {
                val name = inp.readUTF()
                val node = r.node()
                byName[name]?.let { restore.into(it, node) }
            }
            // What the groups work out once a month is worked out again (it is not saved).
            sim.groups.bearings.forget()
            return day
        }
    }

    /**
     * A generated world kept on disk, so a run need not generate it again: [load] gives it back if
     * one for [cfg] was saved by the same world-generating code, else generates it with [log] and
     * keeps it. -Dhex.noWorldCache turns this off.
     */
    object WorldCache {
        private const val WORLD_MAGIC = 0x48455857 // "HEXW"
        val dir = File(System.getProperty("user.home"), ".inflation/world-cache")

        fun load(cfg: inflation.world.WorldConfig, log: (String) -> Unit = {}): inflation.world.World {
            if (System.getProperty("hex.noWorldCache") != null) return inflation.world.World.generate(cfg, log)
            val f = File(dir, "world-%016x.bin".format(key(cfg)))
            if (f.exists()) runCatching {
                DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
                    require(inp.readInt() == WORLD_MAGIC)
                    log("loading the world kept in ${f.name}")
                    val w = Restorer().make(Reader(inp).node(), null, Any::class.java) as inflation.world.World
                    return w
                }
            }.onFailure { System.err.println("could not load the kept world ${f.name}: ${it.message}; generating it again") }
            val w = inflation.world.World.generate(cfg, log)
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
            // The hex model's own classes live in the same package but play no part in making the world.
            for ((n, bytes) in names.sortedBy { it.first }) {
                val simple = n.substringAfterLast('/')
                if (simple.startsWith("Hex") || simple.startsWith("Weather") || simple.startsWith("LiveWater") || simple.startsWith("Eruption")) continue
                crc.update(n.toByteArray()); crc.update(bytes())
            }
            return crc.value xor (cfg.seed shl 32)
        }
    }

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

    private val fieldCache = HashMap<Class<*>, List<Field>>()
    private fun fields(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
        val out = ArrayList<Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) if (!Modifier.isStatic(f.modifiers) && !Modifier.isTransient(f.modifiers)) { f.isAccessible = true; out += f }
            k = k.superclass
        }
        out
    }

    private object T {
        const val NULL = 0; const val SKIP = 1; const val BOOL = 2; const val BYTE = 3; const val SHORT = 4; const val CHAR = 5
        const val INT = 6; const val LONG = 7; const val FLOAT = 8; const val DOUBLE = 9; const val STRING = 10; const val ENUM = 11
        const val SPECIES = 12; const val FLOATS = 13; const val INTS = 14; const val DOUBLES = 15; const val BYTES = 16
        const val BOOLS = 17; const val LONGS = 18; const val SHORTS = 19; const val ARRAY = 20; const val LIST = 21
        const val SET = 22; const val MAP = 23; const val REF = 24; const val OBJ = 25; const val ENUMS = 26; const val LAZY = 27
    }

    /** Writes values to [out]: objects of the classes [keep] accepts field by field, and with [lazies] what lazy fields hold (worked out first if need be). */
    private class Writer(val out: DataOutputStream, val keep: (Class<*>) -> Boolean, val lazies: Boolean) {
        val ids = IdentityHashMap<Any, Int>()
        val buf: ByteBuffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.BIG_ENDIAN)

        fun value(v: Any?) {
            when (v) {
                null -> out.writeByte(T.NULL)
                is Boolean -> { out.writeByte(T.BOOL); out.writeBoolean(v) }
                is Byte -> { out.writeByte(T.BYTE); out.writeByte(v.toInt()) }
                is Short -> { out.writeByte(T.SHORT); out.writeShort(v.toInt()) }
                is Char -> { out.writeByte(T.CHAR); out.writeChar(v.code) }
                is Int -> { out.writeByte(T.INT); out.writeInt(v) }
                is Long -> { out.writeByte(T.LONG); out.writeLong(v) }
                is Float -> { out.writeByte(T.FLOAT); out.writeFloat(v) }
                is Double -> { out.writeByte(T.DOUBLE); out.writeDouble(v) }
                is String -> { out.writeByte(T.STRING); out.writeUTF(v) }
                is Enum<*> -> { out.writeByte(T.ENUM); out.writeUTF(v.declaringJavaClass.name); out.writeUTF(v.name) }
                is Species -> { out.writeByte(T.SPECIES); out.writeUTF(v.name) }
                is FloatArray -> { out.writeByte(T.FLOATS); out.writeInt(v.size); bulk(v.size, 4) { b, i -> b.putFloat(v[i]) } }
                is IntArray -> { out.writeByte(T.INTS); out.writeInt(v.size); bulk(v.size, 4) { b, i -> b.putInt(v[i]) } }
                is DoubleArray -> { out.writeByte(T.DOUBLES); out.writeInt(v.size); bulk(v.size, 8) { b, i -> b.putDouble(v[i]) } }
                is LongArray -> { out.writeByte(T.LONGS); out.writeInt(v.size); bulk(v.size, 8) { b, i -> b.putLong(v[i]) } }
                is ShortArray -> { out.writeByte(T.SHORTS); out.writeInt(v.size); bulk(v.size, 2) { b, i -> b.putShort(v[i]) } }
                is ByteArray -> { out.writeByte(T.BYTES); out.writeInt(v.size); out.write(v) }
                is BooleanArray -> { out.writeByte(T.BOOLS); out.writeInt(v.size); for (x in v) out.writeBoolean(x) }
                is Array<*> -> if (v.javaClass.componentType.isEnum) {
                    out.writeByte(T.ENUMS); out.writeUTF(v.javaClass.componentType.name); out.writeInt(v.size)
                    bulk(v.size, 4) { b, i -> b.putInt((v[i] as Enum<*>?)?.ordinal ?: -1) }
                } else { out.writeByte(T.ARRAY); out.writeUTF(v.javaClass.componentType.name); out.writeInt(v.size); for (x in v) value(x) }
                is Lazy<*> -> if (lazies) { out.writeByte(T.LAZY); value(v.value) } else out.writeByte(T.SKIP)
                is List<*> -> { out.writeByte(T.LIST); out.writeInt(v.size); for (x in v) value(x) }
                is Set<*> -> { out.writeByte(T.SET); out.writeInt(v.size); for (x in v) value(x) }
                is Map<*, *> -> { out.writeByte(T.MAP); out.writeInt(v.size); for ((a, b) in v) { value(a); value(b) } }
                else -> obj(v)
            }
        }

        private fun obj(v: Any) {
            val c = v.javaClass
            if (!keep(c)) { out.writeByte(T.SKIP); return }
            ids[v]?.let { out.writeByte(T.REF); out.writeInt(it); return }
            val id = ids.size; ids[v] = id
            out.writeByte(T.OBJ); out.writeInt(id); out.writeUTF(c.name)
            val fs = fields(c)
            out.writeInt(fs.size)
            for (f in fs) { out.writeUTF(f.name); value(f.get(v)) }
        }

        private inline fun bulk(n: Int, width: Int, put: (ByteBuffer, Int) -> Unit) {
            var i = 0
            while (i < n) {
                buf.clear()
                while (i < n && buf.remaining() >= width) { put(buf, i); i++ }
                out.write(buf.array(), 0, buf.position())
            }
        }
    }

    // ---- reading: first into a tree of nodes, then into the live objects

    private sealed class Node
    private object Skip : Node()
    private class Value(val v: Any?) : Node()
    private class Arr(val component: String, val items: List<Node>) : Node()
    private class Many(val kind: Int, val items: List<Node>) : Node()
    private class Pairs(val items: List<Pair<Node, Node>>) : Node()
    private class Ref(val id: Int) : Node()
    private class LazyNode(val value: Node) : Node()
    private class Obj(val id: Int, val cls: String, val fields: Map<String, Node>) : Node()

    private class Reader(val inp: DataInputStream) {
        val bytes = ByteArray(1 shl 16)

        fun node(): Node = when (val t = inp.readUnsignedByte()) {
            T.NULL -> Value(null)
            T.SKIP -> Skip
            T.BOOL -> Value(inp.readBoolean())
            T.BYTE -> Value(inp.readByte())
            T.SHORT -> Value(inp.readShort())
            T.CHAR -> Value(inp.readChar())
            T.INT -> Value(inp.readInt())
            T.LONG -> Value(inp.readLong())
            T.FLOAT -> Value(inp.readFloat())
            T.DOUBLE -> Value(inp.readDouble())
            T.STRING -> Value(inp.readUTF())
            T.ENUM -> { val c = inp.readUTF(); val n = inp.readUTF()
                Value(runCatching { Class.forName(c).enumConstants.firstOrNull { (it as Enum<*>).name == n } }.getOrNull()) }
            T.SPECIES -> { val n = inp.readUTF(); Value(Catalogue.all.firstOrNull { it.name == n }) }
            T.FLOATS -> { val n = inp.readInt(); val a = FloatArray(n); bulk(n, 4) { b, i -> a[i] = b.getFloat() }; Value(a) }
            T.INTS -> { val n = inp.readInt(); val a = IntArray(n); bulk(n, 4) { b, i -> a[i] = b.getInt() }; Value(a) }
            T.DOUBLES -> { val n = inp.readInt(); val a = DoubleArray(n); bulk(n, 8) { b, i -> a[i] = b.getDouble() }; Value(a) }
            T.LONGS -> { val n = inp.readInt(); val a = LongArray(n); bulk(n, 8) { b, i -> a[i] = b.getLong() }; Value(a) }
            T.SHORTS -> { val n = inp.readInt(); val a = ShortArray(n); bulk(n, 2) { b, i -> a[i] = b.getShort() }; Value(a) }
            T.BYTES -> { val a = ByteArray(inp.readInt()); inp.readFully(a); Value(a) }
            T.BOOLS -> { val a = BooleanArray(inp.readInt()); for (i in a.indices) a[i] = inp.readBoolean(); Value(a) }
            T.ENUMS -> { val c = Class.forName(inp.readUTF()); val n = inp.readInt(); val all = c.enumConstants
                val a = java.lang.reflect.Array.newInstance(c, n)
                @Suppress("UNCHECKED_CAST") val arr = a as Array<Any?>
                bulk(n, 4) { b, i -> val o = b.getInt(); arr[i] = if (o >= 0) all[o] else null }; Value(a) }
            T.LAZY -> LazyNode(node())
            T.ARRAY -> { val c = inp.readUTF(); val n = inp.readInt(); Arr(c, List(n) { node() }) }
            T.LIST, T.SET -> { val n = inp.readInt(); Many(t, List(n) { node() }) }
            T.MAP -> { val n = inp.readInt(); Pairs(List(n) { node() to node() }) }
            T.REF -> Ref(inp.readInt())
            T.OBJ -> { val id = inp.readInt(); val c = inp.readUTF(); val n = inp.readInt()
                val fs = LinkedHashMap<String, Node>(); repeat(n) { val name = inp.readUTF(); fs[name] = node() }; Obj(id, c, fs) }
            else -> error("bad tag $t in saved world")
        }

        private inline fun bulk(n: Int, width: Int, get: (ByteBuffer, Int) -> Unit) {
            var i = 0
            while (i < n) {
                val k = minOf(n - i, bytes.size / width)
                inp.readFully(bytes, 0, k * width)
                val b = ByteBuffer.wrap(bytes, 0, k * width).order(ByteOrder.BIG_ENDIAN)
                repeat(k) { get(b, i); i++ }
            }
        }
    }

    private class Restorer {
        val made = HashMap<Int, Any>()

        /** Restores the saved [node] into the live object [target], field by field. */
        fun into(target: Any, node: Node) {
            if (node !is Obj) return
            made[node.id] = target
            fill(target, node)
        }

        private fun fill(target: Any, node: Obj) {
            for (f in fields(target.javaClass)) {
                val saved = node.fields[f.name] ?: continue
                val cur = f.get(target)
                val v = make(saved, cur, f.type)
                if (v === cur || v === Skip) continue
                if (v == null && f.type.isPrimitive) continue
                if (v != null && !box(f.type).isInstance(v)) continue // the field changed type
                runCatching { f.set(target, v) }
            }
        }

        /** The live value for a saved [node], reusing [cur] where it can; [Skip] to leave things as they are. */
        fun make(node: Node, cur: Any?, type: Class<*>): Any? = when (node) {
            Skip -> Skip
            is Value -> {
                val v = node.v
                when {
                    v is FloatArray && cur is FloatArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is IntArray && cur is IntArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is DoubleArray && cur is DoubleArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is ByteArray && cur is ByteArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is BooleanArray && cur is BooleanArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is LongArray && cur is LongArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    v is ShortArray && cur is ShortArray && cur.size == v.size -> { v.copyInto(cur); cur }
                    else -> v
                }
            }
            is Arr -> {
                @Suppress("UNCHECKED_CAST")
                val a = if (cur is Array<*> && cur.size == node.items.size) cur as Array<Any?>
                    else runCatching { java.lang.reflect.Array.newInstance(Class.forName(node.component), node.items.size) as Array<Any?> }.getOrNull()
                if (a == null) Skip
                else { for (i in node.items.indices) { val v = make(node.items[i], a.getOrNull(i), a.javaClass.componentType); if (v !== Skip) a[i] = v }; a }
            }
            is Many -> {
                val old = (cur as? Collection<*>)?.toList() ?: emptyList()
                // What was not saved (a learned policy, say) stays as it was.
                val items = node.items.mapIndexed { i, n -> make(n, old.getOrNull(i), Any::class.java).let { if (it === Skip) old.getOrNull(i) else it } }
                @Suppress("UNCHECKED_CAST")
                val inPlace = cur is MutableCollection<*> && runCatching { (cur as MutableCollection<Any?>).clear(); cur.addAll(items) }.isSuccess
                when {
                    inPlace -> cur
                    node.kind == T.SET -> LinkedHashSet(items)
                    else -> ArrayList(items)
                }
            }
            is Pairs -> {
                @Suppress("UNCHECKED_CAST")
                val m = (cur as? MutableMap<Any?, Any?>) ?: LinkedHashMap()
                val old = HashMap(m)
                m.clear()
                for ((kn, vn) in node.items) {
                    val k = make(kn, null, Any::class.java); if (k === Skip) continue
                    val v = make(vn, old[k], Any::class.java)
                    m[k] = if (v === Skip) old[k] else v
                }
                m
            }
            is Ref -> made[node.id] ?: Skip
            is LazyNode -> make(node.value, null, Any::class.java).let { if (it === Skip) Skip else lazyOf(it) }
            is Obj -> {
                // Live objects of the same kind are restored in place (others may hold them); values
                // (data classes) and objects the fresh model lacks are made anew.
                val cls = runCatching { Class.forName(node.cls) }.getOrNull()
                when {
                    cls == null -> Skip
                    cur != null && cur.javaClass == cls && !isValue(cls) -> { made[node.id] = cur; fill(cur, node); cur }
                    else -> { val o = blank(cls); made[node.id] = o; fill(o, node); o }
                }
            }
        }

        private fun isValue(c: Class<*>) = c.declaredMethods.any { it.name == "component1" }

        private fun box(c: Class<*>): Class<*> = when (c) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java; java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java; java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java; java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java; java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> c
        }

        /** An object of [c] without running its constructor; its fields are then filled from the save. */
        private fun blank(c: Class<*>): Any {
            val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
            return rf.newConstructorForSerialization(c, Any::class.java.getDeclaredConstructor()).newInstance()
        }
    }
}
