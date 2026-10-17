package inflation.run

import inflation.Config
import java.io.File
import java.util.Properties

/** The run's own config (the keys of [base], by default the file this run read), with [over] set on top. */
fun configWith(over: Map<String, String>, base: File? = Config.source): Properties {
    val p = Properties()
    base?.let { f -> f.reader().use { p.load(it) } }
    for ((k, v) in over) p[k] = v
    return p
}

/**
 * Starts hex-fauna-check with [args] in a JVM of its own (the config is read once at a JVM's start),
 * reading [config], with [heap] (a -Xmx argument), its output to [output].
 */
fun startFaunaCheck(config: File, heap: String, args: List<String>, output: File): Process {
    val javaBin = ProcessHandle.current().info().command().orElse("java")
    return ProcessBuilder(listOf(javaBin, heap, "-Dhex.config=${config.absolutePath}", "-Duser.home=${System.getProperty("user.home")}",
        "-cp", System.getProperty("java.class.path"), "inflation.tools.HexFaunaCheckKt") + args)
        .redirectErrorStream(true).redirectOutput(output).start()
}
