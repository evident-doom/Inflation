package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Reads one key, so that the config is loaded in a fresh JVM with the switches the test gives it. */
object ConfigProbe {
    @JvmStatic fun main(args: Array<String>) { println(Config.string("people") ?: "-") }
}

class ConfigTest {
    /** Runs [ConfigProbe] in a fresh JVM with [props] (-D switches) and the config [file]; its exit code and what it printed. */
    private fun probe(props: List<String>, file: File? = null): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-cp", System.getProperty("java.class.path")) + props + listOfNotNull(file?.let { "-Dhex.config=$it" } ?: "-Dhex.config=none") + ConfigProbe::class.java.name
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test
    fun `an old -D switch stops the run and names the key to set instead`() {
        val (code, out) = probe(listOf("-Dhex.rounds=true"))
        assertNotEquals(0, code)
        assertTrue("no longer read" in out && "rounds=true" in out, out)
    }

    @Test
    fun `a switch that is gone stops the run too`() {
        val (code, out) = probe(listOf("-Dhex.trade=true"))
        assertNotEquals(0, code)
        assertTrue("trade" in out && "gone" in out, out)
    }

    @Test
    fun `a key that is no longer read in the config file stops the run`() {
        val f = File.createTempFile("inflation", ".properties")
        try {
            f.writeText("noPeople=true\n")
            val (code, out) = probe(emptyList(), f)
            assertNotEquals(0, code)
            assertTrue("no longer read" in out, out)
        } finally { f.delete() }
    }

    @Test
    fun `a key in the config file is read`() {
        val f = File.createTempFile("inflation", ".properties")
        try {
            f.writeText("people=false\n")
            val (code, out) = probe(emptyList(), f)
            assertEquals(0, code, out)
            assertEquals("false", out.lines().last { it.isNotBlank() })
        } finally { f.delete() }
    }
}
