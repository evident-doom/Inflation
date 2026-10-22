package inflation

import java.io.File

/**
 * Stops anything that runs in a Claude Code cloud container (Olli 2026-10-22: nothing is run or tested there; runs
 * go to the local PC, long ones to katastrofialue). Called when [Config] starts, which every run, tool and test
 * passes through; build.gradle.kts holds the same check for the Gradle tasks, so they fail before a JVM starts.
 *
 * A cloud container is told by what its session sets: CLAUDE_CODE_REMOTE=true, a container id, or the /root/.ccr
 * directory. None of these exists on Olli's PC or on katastrofialue. GitHub Actions (GITHUB_ACTIONS=true) is let
 * through: CI is how a cloud-written change is built and tested.
 */
object CloudGuard {
    const val MESSAGE = "Use local pc instead"

    fun inCloudContainer(): Boolean =
        System.getenv("GITHUB_ACTIONS") != "true" && (
            System.getenv("CLAUDE_CODE_REMOTE") == "true" ||
                !System.getenv("CLAUDE_CODE_CONTAINER_ID").isNullOrBlank() ||
                File("/root/.ccr").isDirectory)

    fun check() {
        if (inCloudContainer()) error(MESSAGE)
    }
}
