package inflation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Reads one key, so that the config is loaded in a fresh JVM with the switches the test gives it. */
object ConfigProbe {
    @JvmStatic fun main(args: Array<String>) { println(Config.string(Config.Key.PEOPLE) ?: "-") }
}

class ConfigTest {
    /** Runs [ConfigProbe] in a fresh JVM with [props] (-D switches) and the config [file]; its exit code and what it printed. */
    private fun probe(props: List<String>, file: File? = null): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-cp", System.getProperty("java.class.path")) + props +
            listOfNotNull(file?.let { "-Dhex.config=$it" } ?: "-Dhex.config=none") + ConfigProbe::class.java.name
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

    @Test
    fun `a key the code does not read stops the run at once`() {
        val f = File.createTempFile("inflation", ".properties")
        try {
            f.writeText("net=runs/x\n")
            val (code, out) = probe(emptyList(), f)
            assertNotEquals(0, code)
            assertTrue("does not read" in out && "net" in out, out)
        } finally { f.delete() }
    }

    @Test
    fun `every other key is listed in the example config`() {
        val listed = File("config.example.properties").readLines().mapNotNull { Regex("""^#?([\w.]+)=""").find(it.trim())?.groupValues?.get(1) }.toSet()
        assertEquals(emptyList(), Config.Key.entries.map { it.key }.filter { it !in listed }, "keys the code reads that config.example.properties does not list")
        assertEquals(emptyList(), listed.filter { it !in Config.known() }.sorted(), "keys config.example.properties lists that the code does not read")
    }

    @Test
    fun `a tuning key the run never reads is named as it ends`() {
        val f = File.createTempFile("inflation", ".properties")
        try {
            f.writeText("people=false\nt.saltKeep=0.9\n")
            val (code, out) = probe(emptyList(), f)
            assertEquals(0, code, out)
            assertTrue("never read" in out && "t.saltKeep" in out, out)
        } finally { f.delete() }
    }

    /** Every tuning key the code reads ([Config.known]), with its default where [Tuning] writes it as a number: "t.name" to "1.5", or to null. */
    private fun keysInCode(): Map<String, String?> {
        val tuning = Regex("""\b[fdib]\("(\w+)",\s*([^)]+)\)""")
        val defaults = tuning.findAll(File("src/main/kotlin/inflation/Tuning.kt").readText()).associate { "t." + it.groupValues[1] to it.groupValues[2] }
        return Config.known().filter { it.startsWith("t.") }.associateWith { defaults[it]?.trim()?.removeSuffix("f")?.toDoubleOrNull()?.toString() }
    }

    @Test
    fun `every tuning key is listed in the example config, with its default`() {
        val listed = File("config.example.properties").readLines().mapNotNull { Regex("""^#?(t\.\w+)=(.*)$""").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].trim().toDoubleOrNull()?.toString() }
        val code = keysInCode()
        assertTrue(code.size > 40, "found only ${code.size} tuning keys in the code")
        val missing = code.keys.filter { it !in listed }.sorted()
        assertEquals(emptyList(), missing, "tuning keys the code reads that config.example.properties does not list")
        val wrong = code.filter { (k, d) -> d != null && listed[k] != null && d != listed[k] }.map { (k, d) -> "$k: code $d, example ${listed[k]}" }
        assertEquals(emptyList(), wrong, "defaults that differ between the code and config.example.properties")
        val stale = listed.keys.filter { it !in code }.sorted()
        assertEquals(emptyList(), stale, "keys config.example.properties lists that the code no longer reads")
    }
}
