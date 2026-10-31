package inflation.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** A table kept by an enum that has grown since the save (a kind added at the end) keeps the fresh model's values for the new ones, whatever its type. */
class GrownArraysTest {
    class Before { val knows = booleanArrayOf(true, false); val used = intArrayOf(4, 5); val level = floatArrayOf(0.5f, 0.25f) }
    class After { val knows = BooleanArray(3) { it == 2 }; val used = IntArray(3) { 9 }; val level = FloatArray(3) { 1f } }

    @Test
    fun `grown tables keep the new entries' fresh values`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { Writer(it, { c -> c.name.startsWith("inflation.") }, lazies = false).value(Before()) }
        val node = Reader(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))).node()
        val after = After()
        Restorer().into(after, node)
        assertEquals(listOf(true, false, true), after.knows.toList())
        assertEquals(listOf(4, 5, 9), after.used.toList())
        assertEquals(listOf(0.5f, 0.25f, 1f), after.level.toList())
    }
}
