package inflation.store

import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/*
 * Objects written to a stream field by field and read back into live objects (or new ones), by field
 * names rather than layout: what [inflation.ecology.HexSnapshot] saves a running model with, and what
 * the world caches keep generated worlds with. See HexSnapshot for what a restore keeps and drops.
 */

/** Values of [type] written by name only (e.g. a kind of animal, which the code defines), and found again by [find]. */
class Named<T : Any>(val type: Class<T>, private val name: (T) -> String, val find: (String) -> T?) {
    @Suppress("UNCHECKED_CAST")
    fun nameOf(v: Any): String = name(v as T)
}

private val fieldCache = HashMap<Class<*>, List<Field>>()
internal fun fields(c: Class<*>): List<Field> = fieldCache.getOrPut(c) {
    val out = ArrayList<Field>()
    var k: Class<*>? = c
    while (k != null && k != Any::class.java) {
        for (f in k.declaredFields) if (!Modifier.isStatic(f.modifiers) && !Modifier.isTransient(f.modifiers)) { f.isAccessible = true; out += f }
        k = k.superclass
    }
    out
}

internal object T {
    const val NULL = 0; const val SKIP = 1; const val BOOL = 2; const val BYTE = 3; const val SHORT = 4; const val CHAR = 5
    const val INT = 6; const val LONG = 7; const val FLOAT = 8; const val DOUBLE = 9; const val STRING = 10; const val ENUM = 11
    const val NAMED = 12; const val FLOATS = 13; const val INTS = 14; const val DOUBLES = 15; const val BYTES = 16
    const val BOOLS = 17; const val LONGS = 18; const val SHORTS = 19; const val ARRAY = 20; const val LIST = 21
    const val SET = 22; const val MAP = 23; const val REF = 24; const val OBJ = 25; const val ENUMS = 26; const val LAZY = 27
}

/**
 * Writes values to [out]: objects of the classes [keep] accepts field by field, and what lazy fields
 * hold once worked out (with [lazies], worked out first if need be).
 */
internal class Writer(val out: DataOutputStream, val keep: (Class<*>) -> Boolean, val lazies: Boolean, val named: Named<*>? = null) {
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
            is Lazy<*> -> if (lazies || v.isInitialized()) { out.writeByte(T.LAZY); value(v.value) } else out.writeByte(T.SKIP)
            is List<*> -> { out.writeByte(T.LIST); out.writeInt(v.size); for (x in v) value(x) }
            is Set<*> -> { out.writeByte(T.SET); out.writeInt(v.size); for (x in v) value(x) }
            is Map<*, *> -> { out.writeByte(T.MAP); out.writeInt(v.size); for ((a, b) in v) { value(a); value(b) } }
            else -> if (named != null && named.type.isInstance(v)) { out.writeByte(T.NAMED); out.writeUTF(named.nameOf(v)) } else obj(v)
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

internal sealed class Node
internal object Skip : Node()
internal class Value(val v: Any?) : Node()
internal class Arr(val component: String, val items: List<Node>) : Node()
internal class Many(val kind: Int, val items: List<Node>) : Node()
internal class Pairs(val items: List<Pair<Node, Node>>) : Node()
internal class Ref(val id: Int) : Node()
internal class LazyNode(val value: Node) : Node()
internal class Obj(val id: Int, val cls: String, val fields: Map<String, Node>) : Node()

internal class Reader(val inp: DataInputStream, val named: Named<*>? = null) {
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
        T.NAMED -> { val n = inp.readUTF(); Value(requireNotNull(named) { "a value saved by name, and nothing to find it by" }.find(n)) }
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
        else -> error("bad tag $t in saved state")
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

internal class Restorer {
    val made = HashMap<Int, Any>()
    /** What the save held that the code no longer has, or has as another type, and so was dropped ("Class.field", "class Name"). */
    val dropped = sortedSetOf<String>()

    /** Restores the saved [node] into the live object [target], field by field. */
    fun into(target: Any, node: Node) {
        if (node !is Obj) return
        made[node.id] = target
        fill(target, node)
    }

    private fun fill(target: Any, node: Obj) {
        val fs = fields(target.javaClass)
        if (node.fields.size > fs.size || fs.any { it.name !in node.fields }) { val names = fs.mapTo(HashSet()) { it.name }; for (k in node.fields.keys) if (k !in names) dropped += "${target.javaClass.simpleName}.$k" }
        for (f in fs) {
            val saved = node.fields[f.name] ?: continue
            val cur = f.get(target)
            val v = make(saved, cur, f.type)
            if (v === cur || v === Skip) continue
            if (v == null && f.type.isPrimitive) continue
            if (v != null && !box(f.type).isInstance(v)) { dropped += "${target.javaClass.simpleName}.${f.name} (now another type)"; continue }
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
                // A table that has grown (a kind added at the end) keeps what the fresh model gave the new ones.
                v is FloatArray && cur is FloatArray && cur.size > v.size -> { v.copyInto(cur); cur }
                v is DoubleArray && cur is DoubleArray && cur.size > v.size -> { v.copyInto(cur); cur }
                v is IntArray && cur is IntArray && cur.size > v.size -> { v.copyInto(cur); cur }
                else -> v
            }
        }
        is Arr -> {
            @Suppress("UNCHECKED_CAST")
            val a = if (cur is Array<*> && cur.size >= node.items.size) cur as Array<Any?>
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
                val kept = if (v === Skip) old[k] else v
                // (A concurrent map holds no nulls: an entry whose value could not be made is left out.)
                if (kept == null && m is java.util.concurrent.ConcurrentMap<*, *>) continue
                m[k] = kept
            }
            m
        }
        is Ref -> made[node.id] ?: Skip
        // A live lazy field is worked out and restored in place, so that what holds it keeps it.
        is LazyNode -> if (cur is Lazy<*>) {
            val live = cur.value
            make(node.value, live, Any::class.java).let { if (it === Skip || it === live) cur else lazyOf(it) }
        } else make(node.value, null, Any::class.java).let { if (it === Skip) Skip else lazyOf(it) }
        is Obj -> {
            // Live objects of the same kind are restored in place (others may hold them); values
            // (data classes) and objects the fresh model lacks are made anew.
            val cls = runCatching { Class.forName(node.cls) }.getOrNull()
            when {
                cls == null -> { dropped += "class ${node.cls}"; Skip }
                cur != null && cur.javaClass == cls && !isValue(cls) -> { made[node.id] = cur; fill(cur, node); cur }
                else -> { val o = blank(cls); made[node.id] = o; fill(o, node); o }
            }
        }
    }

    private fun isValue(c: Class<*>) = c.declaredMethods.any { it.name == "component1" }

    private fun box(c: Class<*>): Class<*> = when (c) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType; Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType; Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType; Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType; Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> c
    }

    /** An object of [c], made without its constructor if it needs arguments; its fields are then filled from the save. */
    private fun blank(c: Class<*>): Any {
        // A class that can be made without arguments gets its fields' defaults (so fields added since the save are set).
        runCatching { return c.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() }
        val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
        return rf.newConstructorForSerialization(c, Any::class.java.getDeclaredConstructor()).newInstance()
    }
}
