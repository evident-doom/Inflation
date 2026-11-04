package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The diagnostic ablations ([Ablate], t.ablate): how the key reads, and that each mechanism names a real part. */
class AblateTest {
    @Test
    fun `empty means nothing is ablated`() {
        for (text in listOf(null, "", "  ")) {
            val a = Ablate.parse(text)
            assertFalse(a.any)
            for (m in Ablation.entries) { assertFalse(a.off(m)); assertEquals(1, a.every(m)); assertEquals(0, a.frozen(m)) }
        }
    }

    @Test
    fun `forms read as given`() {
        val a = Ablate.parse("tellOfCountry:off, cultureWeek:coarse4,huntingOdds:FROZEN3")
        assertTrue(a.any)
        assertTrue(a.off(Ablation.TELL_OF_COUNTRY))
        assertFalse(a.off(Ablation.KEEP_OFF))
        assertEquals(4, a.every(Ablation.CULTURE_WEEK))
        assertEquals(3, a.frozen(Ablation.HUNTING_ODDS))
        assertEquals(0, a.frozen(Ablation.CULTURE_WEEK))
    }

    @Test
    fun `a wrong key or form stops the run`() {
        for (bad in listOf("nothing:off", "tellOfCountry", "tellOfCountry:coarse4", "cultureWeek:off", "cultureWeek:coarse", "cultureWeek:coarse1",
            "huntingOdds:frozen0", "keepOff:off,keepOff:off", "keepOff:sometimes"))
            assertFailsWith<IllegalArgumentException>(bad) { Ablate.parse(bad) }
    }

    @Test
    fun `frozen odds are reused only for the same ground while young enough`() {
        val a = Ablate.parse("huntingOdds:frozen4")
        val ground = intArrayOf(5, 7, 9)
        assertEquals(null, a.oddsFor(1, 10, ground, 3))
        a.keepOdds(1, Ablate.FrozenOdds(10, ground.copyOf(), FloatArray(3), FloatArray(3), FloatArray(3)))
        assertTrue(a.oddsFor(1, 13, ground, 3) != null)
        assertEquals(null, a.oddsFor(1, 14, ground, 3), "4 weeks on, worked out again")
        a.keepOdds(1, Ablate.FrozenOdds(14, ground.copyOf(), FloatArray(3), FloatArray(3), FloatArray(3)))
        assertEquals(null, a.oddsFor(1, 15, intArrayOf(5, 7, 8), 3), "other ground")
        assertEquals(null, a.oddsFor(2, 15, ground, 3), "another group's")
        assertEquals(null, Ablate.parse(null).oddsFor(1, 10, ground, 3), "nothing frozen")
    }

    @Test
    fun `each mechanism lives in a labelled part`() {
        val root = File(Part::class.java.protectionDomain.codeSource.location.toURI())
        val names = HashSet<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val cls = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val c = try { Class.forName(cls, false, Part::class.java.classLoader) } catch (e: Throwable) { return@forEach }
            val methods = try { c.declaredMethods } catch (e: Throwable) { return@forEach }
            for (m in methods) m.getAnnotation(Part::class.java)?.let { names += it.name }
        }
        for (m in Ablation.entries) assertTrue(m.part in names, "${m.key}: no @Part(\"${m.part}\")")
    }
}
