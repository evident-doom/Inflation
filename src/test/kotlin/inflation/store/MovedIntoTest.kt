package inflation.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A part split out of its owner ([MovedInto]) is filled from a save made before the split, and nothing is reported dropped. */
class MovedIntoTest {
    class Before { val kept = intArrayOf(1, 2); val marks = HashMap(mapOf(3 to floatArrayOf(0.5f))); val markers = HashMap(mapOf(3 to intArrayOf(7))) }
    class Part { val marks = HashMap<Int, FloatArray>(); val markers = HashMap<Int, IntArray>() }
    class After { val kept = IntArray(2); @field:MovedInto("marks", "markers") val part = Part() }

    @Test
    fun `an old save fills the part`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { Writer(it, { c -> c.name.startsWith("inflation.") }, lazies = false).value(Before()) }
        val node = Reader(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))).node()
        val after = After(); val restore = Restorer()
        restore.into(after, node)
        assertEquals(listOf(1, 2), after.kept.toList())
        assertEquals(listOf(0.5f), after.part.marks.getValue(3).toList())
        assertEquals(listOf(7), after.part.markers.getValue(3).toList())
        assertTrue(restore.dropped.isEmpty(), "dropped ${restore.dropped}")
    }
}
