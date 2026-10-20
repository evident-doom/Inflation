package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals

class MetTest {
    @Test
    fun `each meeting has a label of its own`() {
        val all = Met.entries.map { it.label } + Met.Of.entries.map { it.key("x") }
        assertEquals(all.size, all.toSet().size)
    }
}
