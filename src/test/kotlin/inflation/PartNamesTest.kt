package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profiler's labels ([Part]) are unique, so a sample can only belong to one part. */
class PartNamesTest {
    private fun labelled(): List<Pair<String, String>> {
        val root = File(Part::class.java.protectionDomain.codeSource.location.toURI())
        require(root.isDirectory) { "expected the compiled classes in a directory, got $root" }
        val out = ArrayList<Pair<String, String>>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val cls = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val c = try { Class.forName(cls, false, Part::class.java.classLoader) } catch (e: Throwable) { return@forEach }
            val methods = try { c.declaredMethods } catch (e: Throwable) { return@forEach }
            for (m in methods) m.getAnnotation(Part::class.java)?.let { out += it.name to "${c.name}.${m.name}" }
        }
        return out
    }

    @Test
    fun `part names are unique`() {
        val all = labelled()
        assertTrue(all.isNotEmpty(), "no @Part labels found")
        val dup = all.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertEquals(emptyMap(), dup, "part names used on more than one method")
    }
}
