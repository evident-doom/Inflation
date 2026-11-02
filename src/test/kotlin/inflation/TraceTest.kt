package inflation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceTest {
    @Test
    fun `a trace prints its share of cases, up to its most, and only while on`() {
        assertFalse(Trace(Config.Key.PLAN_TRACE, "off", read = { null }).wants(1))
        val all = Trace(Config.Key.PLAN_TRACE, "all", most = 3, read = { "true" })
        assertEquals(listOf(true, true, true, false), (1L..4L).map { all.wants(it) })
        val some = Trace(Config.Key.PLAN_TRACE, "some", share = 0.1f, read = { "true" })
        val drawn = (0L until 10_000L).count { some.wants(it, 40) }
        assertTrue(drawn in 900..1100, "$drawn of 10000 drawn at a share of 0.1")
        val again = Trace(Config.Key.PLAN_TRACE, "some", share = 0.1f, read = { "true" })
        assertEquals((0L until 50L).map { some.wants(it, 41) }, (0L until 50L).map { again.wants(it, 41) }, "the same cases each time")
    }

    @Test
    fun `a trace by kind prints only the kind its key names`() {
        val t = Trace(Config.Key.PLAN_DIAG, "kind", byKind = true, read = { "wolf" })
        assertTrue(t.on)
        assertTrue(t.wants(1, of = "wolf"))
        assertFalse(t.wants(1, of = "lion"))
    }
}
