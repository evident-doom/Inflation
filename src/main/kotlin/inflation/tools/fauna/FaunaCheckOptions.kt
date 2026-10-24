package inflation.tools.fauna

import inflation.Args
import inflation.Config
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.startVillages
import inflation.Tuning
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.WorldConfig
import java.io.File
import inflation.world.WorldCache

/** What a fauna check runs: the world, how long, and the files it reads and writes. */
class FaunaCheckOptions(
    val cfg: WorldConfig,
    val years: Int,
    /** Residents start evenly spread instead of by habitat. */
    val uniform: Boolean,
    val historyFile: File?,
    val saveFile: File?,
    val loadFile: File?,
    /** Draws the weather afresh, after a load or on a fresh world (0: the saved weather, or the seed's). */
    val replicate: Long,
    val testWorld: TestWorlds.Entry?,
) {
    /**
     * Builds the model: the world (from the cache), the saved state it carries on from, and the learned
     * networks the config names ([LearnedModel]). Prints what it loaded.
     */
    fun start(): HexSim {
        val sim = HexSim(WorldCache.load(cfg) { println(it) }, uniformStart = uniform)
        testWorld?.let { TestWorlds.check(it, sim) }
        loadFile?.let { f ->
            val t0 = System.nanoTime()
            HexSnapshot.load(sim, f)
            println("carrying on the world saved in $f, at ${sim.weather.date} (loaded in %.1f s)".format((System.nanoTime() - t0) / 1e9))
            // (The saved state took the place of the villages the fresh world started with: a world carried on starts its
            // villages now, among the bands it carries on with.)
            if (sim.groups.tuning.startSettled > 0f) sim.groups.startVillages()
        }
        // (A fresh world's weather is drawn afresh too, so its replicates are other weathers on the same land.)
        if (replicate != 0L) { sim.weather.reseed(replicate); println("weather drawn afresh: replicate $replicate") }
        Tuning.changed().takeIf { it.isNotEmpty() }?.let { println("tuning: $it") }
        LearnedModel.fromConfig(sim.groups) { println(it) }
        return sim
    }

    companion object {
        private const val USAGE = "hex-fauna-check [--seed N] [--width KM] [--height KM] [--years N] [--save FILE] [--load FILE] [--world NAME] [--replicate R]"

        fun parse(args: Array<String>): FaunaCheckOptions {
            val a = Args(args, USAGE)
            // A test world sets the map and the state to carry on from; an option before --world on the line
            // gives way to it, one after it changes it.
            val testWorld = a.string("--world")?.let { TestWorlds.named(it) }
            val worldAt = args.indexOf("--world")
            fun stands(name: String) = testWorld == null || args.indexOf(name) > worldAt
            var cfg = testWorld?.config ?: WorldConfig(seed = 1)
            val seed = a.long("--seed", cfg.seed); val widthKm = a.int("--width", cfg.widthKm); val heightKm = a.int("--height", cfg.heightKm)
            if (stands("--seed")) cfg = cfg.copy(seed = seed)
            if (stands("--width")) cfg = cfg.copy(widthKm = widthKm)
            if (stands("--height")) cfg = cfg.copy(heightKm = heightKm)
            val years = a.int("--years", 10)
            val historyFile = a.file("--history")
            val saveFile = a.file("--save")
            val loadFile = a.file("--load").takeIf { stands("--load") } ?: testWorld?.state
            val replicate = a.long("--replicate", 0L)
            a.done()
            // Every kind spread evenly over the land at the start (uniformStart=true), instead of by its country.
            val uniform = Config.flag(Config.Key.UNIFORM_START, false)
            // The world's centre latitude (latitude=DEGREES), as the training maps set it; a test world keeps its own.
            if (testWorld == null) Config.string(Config.Key.LATITUDE)?.let { cfg = cfg.copy(centreLatitude = it.toDouble()) }
            return FaunaCheckOptions(cfg, years, uniform, historyFile, saveFile, loadFile, replicate, testWorld)
        }
    }
}
