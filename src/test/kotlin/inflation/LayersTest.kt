package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The packages build on one another in layers, and no code reaches up: the root package (the run's settings, draws,
 * loops, units) at the bottom with the world's index types and its config; then the world and the store; the ecology;
 * the runs; the learning; the tools and the viewer on top. Documentation links may point anywhere.
 */
class LayersTest {
    private val layer = mapOf("" to 0, "world" to 1, "store" to 1, "ecology" to 2, "run" to 3, "learning" to 4, "tools" to 5, "ui" to 5)
    /** The world's files the root package builds on: the index types and the world's config, which depend on nothing of the world. */
    private val vocabulary = setOf("Hex", "HexArray", "HexList", "Place", "PlaceList", "WorldConfig")

    @Test
    fun `no code reaches up a layer`() {
        val root = File("src/main/kotlin/inflation")
        val ref = Regex("""inflation\.(world|store|ecology|run|learning|tools|ui)\.(\w+)""")
        val found = ArrayList<String>()
        root.walkTopDown().filter { it.name.endsWith(".kt") }.forEach { f ->
            val pkg = f.parentFile.relativeTo(root).path.substringBefore(File.separatorChar)
            val mine = layer.getValue(pkg)
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return@forEachIndexed
                val code = line.replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"").replace(Regex("""\[[^\]]*]"""), "").substringBefore("//")
                for (m in ref.findAll(code)) {
                    val (to, name) = m.destructured
                    if (layer.getValue(to) > mine && !(mine == 0 && to == "world" && name in vocabulary))
                        found += "${f.relativeTo(root).path}:${i + 1}: ${pkg.ifEmpty { "root" }} -> $to.$name"
                }
            }
        }
        assertEquals(emptyList(), found, "code reaching up a layer (move it down, or hand it in from above)")
    }
}
