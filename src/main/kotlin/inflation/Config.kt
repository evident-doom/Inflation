package inflation

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Run settings, read from a properties file of keys (see config.example.properties): the file
 * -Dhex.config=FILE names (none: no file), else inflation.properties in the working directory, else
 * none (every key at its default). A setting left out keeps its default; tuning keys start with "t.".
 *
 * Every key the code reads is a [Key], or a tuning key that [Tuning] reads: a key in the file that is
 * neither (misspelt, or no longer read) stops the run at once, naming it. A tuning key set in the file that
 * the run never reads (of a part that did not run) is named on stderr as the run ends.
 *
 * The old -Dhex.* switches are no longer read: a run given one stops at once and names the key to
 * set instead, so that no run goes on silently without a setting it was meant to have.
 */
object Config {
    /** The keys the code reads, but for the tuning keys ([Tuning], t.NAME): each by its [name] in the file. */
    enum class Key(val key: String) {
        NETS("nets"),
        WAYS("ways"),
        HUNTER_WAYS("hunterWays"),
        LATITUDE("latitude"),
        BASELINES("baselines"),
        ROUNDS("rounds"),
        PEOPLE("people"),
        CLIMATE_SHOCK("climateShock"),
        UNIFORM_START("uniformStart"),
        CHOICE_WARM_WEEKS("choiceWarmWeeks"),
        CHOICE_NOISE("choiceNoise"),
        LEVERAGE_TESTS("leverageTests"),
        LEVERAGE_WAYS("leverageWays"),
        LEVERAGE_WAY_VALUES("leverageWayValues"),
        CHOICE_WAY("choiceWay"),
        CHOICE_WAY_VALUE("choiceWayValue"),
        DIAL_ARM_SIZE("dialArmSize"),
        DIAL_ARM_WEEKS("dialArmWeeks"),
        DIAL_ARM_WAYS("dialArmWays"),
        DIAL_ARM_VALUES("dialArmValues"),
        DIAL_ARM_GROUPS("dialArmGroups"),
        DIAL_ARM_STRESS_TOP("dialArmStressTop"),
        DIAL_ARM_LEAD_WEEKS("dialArmLeadWeeks"),
        PPO_HIDDEN("ppo.hidden"),
        PPO_RATE("ppo.rate"),
        PPO_RESIDUAL("ppo.residual"),
        PPO_PER_KIND("ppo.perKind"),
        PPO_PEOPLE_CHOICES("ppo.peopleChoices"),
        PPO_CHECK_WORLD("ppo.checkWorld"),
        PPO_CHECK_YEARS("ppo.checkYears"),
        PPO_CHECK_REPLICATES("ppo.checkReplicates"),
        PPO_CHECK_PARALLEL("ppo.checkParallel"),
        PPO_CHECK_HEAP_GB("ppo.checkHeapGb"),
        PPO_HUNTER_PEOPLE_VETO("ppo.hunterPeopleVeto"),
        PPO_KEEP_SIGMA("ppo.keepSigma"),
        PPO_CRITIC_TIMES("ppo.criticTimes"),
        PPO_ADVANTAGE_CLIP("ppo.advantageClip"),
        PPO_FORESEEN_SHAPE("ppo.foreseenShape"),
        PPO_STRESS_BOOST("ppo.stressBoost"),
        PPO_MAX_SHARE("ppo.maxShare"),
        PPO_MOVER_KIT_WEIGHT("ppo.moverKitWeight"),
        PPO_WAYS_PLACE_WEIGHT("ppo.waysPlaceWeight"),
        PPO_SMALL_INPUT_BOOST("ppo.smallInputBoost"),
        PPO_SETTLED_WORLDS("ppo.settledWorlds"),
        WEATHER_STRESS_YEARS("weather.stressYears"),
        PPO_WAYS_TRAIN_MOVER("ppo.waysTrainMover"),
        EVOLVE_WAYS_SIGMA("evolveWays.sigma"),
        EVOLVE_WAYS_RATE("evolveWays.rate"),
        EVOLVE_WAYS_HIDDEN("evolveWays.hidden"),
        WORLD_CACHE("worldCache"),
        LANDFORM_CACHE("landformCache"),
        PROFILE("profile"),
        HUNT_DIAG("huntDiag"),
        PLAN_DIAG("planDiag"),
        FORAGE_DIAG("forageDiag"),
        WARM_DIAG("warmDiag"),
        THATCH_TRACE("thatchTrace"),
        MOVE_TRACE("moveTrace"),
        FOLLOW("follow"),
        FOLLOW_HUNGRY("followHungry"),
        STRAND_DIAG("strandDiag"),
        WAYS_LOG("waysLog"),
        WAYS_FORCE("waysForce"),
        DIAL_USE("dialUse"),
        FED_DIAG("fedDiag"),
        SHRUB_DIAG("shrubDiag"),
        HUNT_STEPS("huntSteps"),
        AUDIT("audit");

        override fun toString() = key
    }

    /** Old switches and the key that now sets them, where it is not the bare name (keys say what is on, not what is off). */
    private val RENAMED = mapOf("rounds" to "rounds=true", "noPeople" to "people=false", "noWorldCache" to "worldCache=false",
        "noLandformCache" to "landformCache=false")
    /** Old switches that are gone: their setting is now the code's. */
    private val GONE = setOf("trade", "taming", "t.noGifts", "t.noSourceRites", "noHerding", "t.noDenning", "t.herdRanges", "t.noProtein",
        "t.noGreenBearing", "noBearings", "t.bandContentWeeks", "t.spanFloor", "t.ambush", "migrantUnit", "migrantStart", "sizeByLeaning",
        "newcomers", "catalogueStart", "t.roundCue", "t.roundSpread", "t.roundFollowGreen", "t.supersense", "t.superTop", "t.superKm",
        "t.superLure", "t.scoutKm", "t.scoutChance", "t.scoutMargin", "t.plateauKm", "t.plateauM", "t.plantEaterHerds", "t.formSwap",
        "t.contentWeeks", "t.huntersHalf", "t.headingKeep", "t.headingMoved", "t.headingPull", "t.roundStrength", "t.roundTrekDays",
        "t.roundDrift", "t.roundSwitch", "t.roundStart", "t.roundKmCost", "t.roundPull", "t.roundNetPull", "herdWays", "migDiag", "crowdDiag", "ppo.migrationWeight")

    /** Config file keys that were renamed, and their name now. */
    private val MOVED = mapOf("t.huntDiag" to "huntSteps")

    private val props = Properties()
    /** The keys set in the file that the run has read. */
    private val read = ConcurrentHashMap.newKeySet<String>()
    val source: File?

    init {
        CloudGuard.check()
        val named = System.getProperty("hex.config")
        source = if (named == "none") null else named?.let { File(it) } ?: File("inflation.properties").takeIf { it.isFile }
        if (source != null) {
            require(source.isFile) { "config file $source not found (-Dhex.config)" }
            source.reader().use { props.load(it) }
        }
        val stray = System.getProperties().stringPropertyNames().filter { it.startsWith("hex.") && it != "hex.config" }.map { it.removePrefix("hex.") }.sorted()
        require(stray.isEmpty()) {
            val (gone, moved) = stray.partition { it in GONE }
            "the -Dhex.* switches are no longer read." +
                (if (moved.isEmpty()) "" else " Set these in the config file instead (see config.example.properties): " +
                    moved.joinToString(", ") { RENAMED[it] ?: it } + ".") +
                (if (gone.isEmpty()) "" else " These are gone, their default now being the code: " + gone.joinToString(", ") + ".")
        }
        val unknown = props.stringPropertyNames().filter { it in GONE || it.startsWith("no") && it in RENAMED }
        require(unknown.isEmpty()) { "config keys no longer read: ${unknown.joinToString(", ")} (see config.example.properties)" }
        val moved = props.stringPropertyNames().filter { it in MOVED }.sorted()
        require(moved.isEmpty()) { "config keys renamed: ${moved.joinToString(", ") { "$it is now ${MOVED[it]}" }}" }
        val known = known()
        val strange = props.stringPropertyNames().filter { it !in known }.sorted()
        require(strange.isEmpty()) { "config keys the code does not read (misspelt?): ${strange.joinToString(", ")} (see config.example.properties)" }
        if (props.stringPropertyNames().any { it.startsWith("t.") }) Runtime.getRuntime().addShutdownHook(Thread {
            unread("t.").takeIf { it.isNotEmpty() }?.let { System.err.println(
                "tuning keys set but never read in this run (misspelt, or of a part that did not run): ${it.joinToString(", ")}") }
        })
    }

    /** Every key the code reads: the [Key]s and the tuning keys (those a [Tuning] asks for as it is made). */
    fun known(): Set<String> = Key.entries.mapTo(HashSet()) { it.key }.also { k -> Tuning({ k += it; null }, { null }).let { } }

    fun string(key: Key): String? = read(key.key)
    fun flag(key: Key, default: Boolean): Boolean = string(key)?.toBooleanStrict() ?: default
    fun float(key: Key, default: Float): Float = string(key)?.toFloat() ?: default
    fun double(key: Key, default: Double): Double = string(key)?.toDouble() ?: default
    fun int(key: Key, default: Int): Int = string(key)?.toInt() ?: default
    /** A tuning key's value ("t.NAME", see [Tuning]). */
    fun tuning(key: String): String? { require(key.startsWith("t.")) { "$key is no tuning key" }; return read(key) }
    private fun read(key: String): String? = props.getProperty(key)?.also { read += key }?.trim()?.takeIf { it.isNotEmpty() }

    /** The keys set under a prefix that the run has not read (so far). */
    fun unread(prefix: String): List<String> = props.stringPropertyNames().filter { it.startsWith(prefix) && it !in read }.sorted()

    /** The keys set under a prefix, as "name value" (for a check's header). */
    fun set(prefix: String): String = props.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()
        .joinToString(", ") { "${it.removePrefix(prefix)} ${read(it)}" }
}

/** The folder [name] under ~/.inflation, where worlds, caches and test worlds are kept between runs. */
fun inflationHome(name: String): File = File(System.getProperty("user.home"), ".inflation/$name")
