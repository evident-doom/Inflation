package inflation

import inflation.ecology.Skill
import inflation.ecology.Tool
import inflation.ecology.startLevels
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EraProfilesTest {
    private val profiles = File("eras").listFiles { f -> f.name.endsWith(".properties") }.orEmpty().sortedBy { it.name }

    @Test
    fun `every era profile sets only keys the code reads, and its ways and tools are known names`() {
        assertTrue(profiles.isNotEmpty(), "no era profiles in eras/")
        val known = Config.known()
        for (f in profiles) {
            val p = Properties().also { q -> f.reader().use { q.load(it) } }
            assertEquals(emptyList(), p.stringPropertyNames().filter { it !in known || it == Config.Key.ERA.key }.sorted(), "${f.name}: keys not read (or an era)")
            val t = Tuning({ p.getProperty(it) }, { null })
            startLevels("t.startSkills", t.startSkills, Skill.entries)
            startLevels("t.startTools", t.startTools, Tool.entries)
        }
    }

    @Test
    fun `a profile is found by its name, and a bad name is refused`() {
        assertTrue(Config.eraFile("ppnb", null).isFile)
        assertTrue(runCatching { Config.eraFile("../x", null) }.isFailure)
        assertTrue(runCatching { Config.eraFile("nosuchera", null) }.isFailure)
    }
}
