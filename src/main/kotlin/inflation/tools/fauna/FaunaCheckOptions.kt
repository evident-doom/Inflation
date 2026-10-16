package inflation.tools.fauna

import inflation.Args
import inflation.Config
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Tuning
import inflation.learning.HexPpo
import inflation.learning.MoverPolicy
import inflation.learning.NetAnimalWays
import inflation.learning.NetWays
import inflation.learning.Role
import inflation.learning.Schema
import inflation.learning.SharedMover
import inflation.tools.TestWorlds
import inflation.tools.baselineDir
import inflation.world.WorldConfig
import java.io.File

/** What a fauna check runs: the world, how long, and the files it reads and writes. */
class FaunaCheckOptions(
    val cfg: WorldConfig,
    val years: Int,
    /** Residents start evenly spread instead of by habitat. */
    val uniform: Boolean,
    val historyFile: File?,
    val saveFile: File?,
    val loadFile: File?,
    /** Draws the weather afresh after a load (0: carries on the saved weather). */
    val replicate: Long,
    val testWorld: TestWorlds.Entry?,
) {
    /**
     * Builds the model: the world (from the cache), the saved state it carries on from, and the learned
     * networks the config names (ways=, herdWays=, hunterWays=, nets=). Prints what it loaded.
     */
    fun start(): HexSim {
        val sim = HexSim(inflation.world.WorldCache.load(cfg) { println(it) }, uniformStart = uniform)
        testWorld?.let { TestWorlds.check(it, sim) }
        loadFile?.let { f ->
            val t0 = System.nanoTime()
            HexSnapshot.load(sim, f)
            println("carrying on the world saved in $f, at ${sim.weather.date} (loaded in %.1f s)".format((System.nanoTime() - t0) / 1e9))
            if (replicate != 0L) { sim.weather.reseed(replicate); println("weather drawn afresh: replicate $replicate") }
        }
        Tuning.changed().takeIf { it.isNotEmpty() }?.let { println("tuning: $it") }
        val groups = sim.groups
        // Bands' ways from a learned network (ways=FILE), else the scaffold's.
        Config.string("ways")?.let { groups.ways = NetWays(Schema.load(File(it), Schema.WAYS_IN, Schema.WAYS_OUT)) }
        // Herds' and packs' ways from learned networks (herdWays=FILE, hunterWays=FILE), else the hand-written ways.
        Config.string("herdWays")?.let { groups.herdWays = NetAnimalWays(Schema.load(File(it), Schema.ANIMAL_WAYS_IN, Schema.HERD_WAYS_OUT)) }
        Config.string("hunterWays")?.let { groups.hunterWays = NetAnimalWays(Schema.load(File(it), Schema.ANIMAL_WAYS_IN, Schema.HUNTER_WAYS_OUT)) }
        // Every kind moving by learned role networks (nets=DIR with herds.mlp, hunters.mlp, people.mlp, as HexPpo writes them), else by the hand-written choices.
        Config.string("nets")?.let { dir ->
            val ps = Role.entries.map { r -> MoverPolicy(SharedMover.load(r.file(baselineDir(dir))), null, { 0f }, { 0 }) }
            for (s in groups.kinds) groups.policies[s] = ps[Role.of(s).ordinal]
            println("moving by the learned networks in $dir")
        }
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
            val uniform = a.flag("--uniform")
            val historyFile = a.file("--history")
            val saveFile = a.file("--save")
            val loadFile = a.file("--load").takeIf { stands("--load") } ?: testWorld?.state
            val replicate = a.long("--replicate", 0L)
            a.done()
            // The world's centre latitude (latitude=DEGREES), as the training maps set it; a test world keeps its own.
            if (testWorld == null) Config.string("latitude")?.let { cfg = cfg.copy(centreLatitude = it.toDouble()) }
            return FaunaCheckOptions(cfg, years, uniform, historyFile, saveFile, loadFile, replicate, testWorld)
        }
    }
}
