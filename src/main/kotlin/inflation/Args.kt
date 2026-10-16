package inflation

import inflation.world.WorldConfig
import java.io.File

/**
 * A program's command line: `--name value` options and bare `--name` flags, each taken by the program
 * as it reads its settings, and plain words in order. [done] stops the run on anything left untaken,
 * so that a mistyped option is an error rather than a run with the default.
 */
class Args(private val args: Array<String>, private val usage: String) {
    private val taken = BooleanArray(args.size)

    private fun at(name: String): Int {
        val i = args.indexOf(name)
        if (i >= 0) taken[i] = true
        return i
    }

    /** The value after [name], or null if [name] is not given. */
    fun string(name: String): String? {
        val i = at(name)
        if (i < 0) return null
        if (i + 1 >= args.size) fail("$name needs a value")
        taken[i + 1] = true
        return args[i + 1]
    }
    fun int(name: String, default: Int) = string(name)?.let { it.toIntOrNull() ?: fail("$name takes a whole number, not $it") } ?: default
    fun long(name: String, default: Long) = string(name)?.let { it.toLongOrNull() ?: fail("$name takes a whole number, not $it") } ?: default
    fun float(name: String, default: Float) = string(name)?.let { it.toFloatOrNull() ?: fail("$name takes a number, not $it") } ?: default
    fun double(name: String, default: Double) = string(name)?.let { it.toDoubleOrNull() ?: fail("$name takes a number, not $it") } ?: default
    fun file(name: String): File? = string(name)?.let { File(it) }
    /** Whether the bare flag [name] is given. */
    fun flag(name: String) = at(name) >= 0

    /** The world's size and seed: --seed, --size (a square map), --width and --height, km. */
    fun world(base: WorldConfig = WorldConfig()): WorldConfig {
        var cfg = base
        long("--seed", -1L).takeIf { it >= 0 }?.let { cfg = cfg.copy(seed = it) }
        int("--size", -1).takeIf { it > 0 }?.let { cfg = cfg.copy(widthKm = it, heightKm = it) }
        int("--width", -1).takeIf { it > 0 }?.let { cfg = cfg.copy(widthKm = it) }
        int("--height", -1).takeIf { it > 0 }?.let { cfg = cfg.copy(heightKm = it) }
        return cfg
    }

    /** The words not taken by an option, in order (call after the options are read). */
    fun rest(): List<String> = args.indices.filter { !taken[it] && !args[it].startsWith("--") }.onEach { taken[it] = true }.map { args[it] }

    /** Stops the run if anything on the command line was not taken. */
    fun done() {
        val left = args.indices.filter { !taken[it] }.map { args[it] }
        if (left.isNotEmpty()) fail("not understood: ${left.joinToString(" ")}")
    }

    private fun fail(why: String): Nothing {
        System.err.println("$why\nUsage: $usage")
        throw IllegalArgumentException(why)
    }
}
