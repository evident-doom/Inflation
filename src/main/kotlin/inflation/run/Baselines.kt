package inflation.run

import inflation.Config
import java.io.File

/** The folder the baselines are kept in: config key "baselines", runs/baselines by default. */
fun baselinesRoot(): File = File(Config.string(Config.Key.BASELINES) ?: "runs/baselines")

/**
 * Where [name] is a baseline's name, its directory; otherwise [name] as a path. A baseline named but not kept here is
 * fetched from the repository where it is the nets kept there ([LatestData]).
 */
fun baselineDir(name: String): File = File(name).takeIf { it.exists() } ?: File(baselinesRoot(), name).takeIf { it.exists() }
    ?: (if ('/' !in name && '\\' !in name) LatestData.baseline(name) else null) ?: File(baselinesRoot(), name)

/** The commit the code was built from (short hash), or "unknown". */
fun gitCommit(): String = try {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (e: Exception) { "unknown" }
