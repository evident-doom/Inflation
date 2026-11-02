package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The model's parallel loops all go through [Par], so that a profile=jfr recording sees every one of them: none in the
 * world's or the ecology's code is a bare parallel stream. (Whole runs side by side, in training and the tools, are not loops of the model.)
 */
class ParLoopsTest {
    @Test
    fun `the model's parallel loops all go through Par`() {
        val raw = Regex("""\.parallel\(\)|parallelStream\(\)""")
        val found = listOf("world", "ecology").flatMap { dir ->
            File("src/main/kotlin/inflation/$dir").walkTopDown().filter { it.name.endsWith(".kt") }.flatMap { f ->
                f.readLines().withIndex().filter { (_, l) -> raw.containsMatchIn(l) }.map { (i, _) -> "$dir/${f.name}:${i + 1}" }
            }.toList()
        }
        assertEquals(emptyList(), found, "parallel loops outside Par (use Par.forEach, forEachPlace or forEachHex)")
    }
}
