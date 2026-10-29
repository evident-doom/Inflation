package inflation.ecology

import inflation.world.Hex
import inflation.world.get
import inflation.world.set
import inflation.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import inflation.world.Weather
import kotlin.math.exp

// Bands' raids and their defence: palisades, plotting and fighting raids.

/** A palisade round the houses at [g]'s band [b]'s camp at hex [h], at home and raided in the last year, as far as the [time] days and its timber go. */
internal fun HexGroups.buildPalisade(g: HexGroup, b: Band, h: Hex, people: Float, time: BandTime) {
    val gd = b.goods
    val left = time.leftFor(Activity.PALISADE)
    if (b.atHome && b.palisade < 1f && left > 0f && people > 0f)
        dialUse?.choice(DialSite.PALISADES, b.fortifyWill, b.sinceRaided < stepsPerYear * b.fortifyWill, b.sinceRaided < stepsPerYear)
    if (fortifies(b) && left > 0f && people > 0f) {
        val s = buildingAt(g, Built.PALISADE, h, -1, people)
        val built = minOf(1f - s.built, left / (PALISADE_DAYS * people), gd[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f)
        gd[Material.WOOD] -= built * PALISADE_WOOD * people; buildOn(s, built); time.spend(Activity.PALISADE, built * PALISADE_DAYS * people)
        if (built > 0f) stats.meeting(Met.BANDS_BUILT_PALISADES)
    }
}

/** Whether [b] rings its houses with a palisade: at home, raided lately (by its will), with the ring not whole. */
internal fun GroupWorld.fortifies(b: Band) = b.atHome && b.sinceRaided < stepsPerYear * b.fortifyWill && b.palisade < 1f

/**
 * A band driven by hunger or a feud, knowing where a band it is not close to has its home within
 * reach, sets out to fall on it there: settled bands can be found. Once there, if the others are at
 * home, it attacks (see [fight], fought in [fightRaids] once every band has had its week); otherwise
 * it gives up.
 */
internal fun HexGroups.plotRaid(g: HexGroup, b: Band, weather: Weather) {
    val here = hw.placeHex[g.place]
    b.raid?.let { raid ->
        if (here == raid.hex) {
            val target = groups.firstOrNull { it.id == raid.on && it.alive && hw.placeHex[it.place] == here }
            if (target?.band != null) bands.raidsDue += g to target
            b.raid = null
        } else b.raid = if (raid.weeks > 1) Raid(raid.hex, raid.on, raid.weeks - 1) else null
        return
    }
    // (A readier band plots on less hunger: each step of readiness closes the same share of what is left of the gap to fed.)
    val hungry = g.fed < shareOfWill(b.raidWill, RAID_HUNGER)
    dialUse?.choice(DialSite.RAIDS_HUNGER, b.raidWill, hungry, g.fed < RAID_HUNGER)
    val within = ArrayList<Int>()
    for ((id, h) in b.knownHomes) {
        val feud = (b.knownLineage[id] ?: -1).let { it >= 0 && b.grudge[it] > FEUD }
        if (!hungry && !feud) continue
        if (hexKm(here, h) <= RAID_RANGE_KM) within += id
    }
    if (within.isEmpty()) return
    // Whether it sets out turns on how it regards the bands it could fall on ([Regard.fightChance]): the warier of one of them,
    // the likelier; never on a band it is close to. (No regard is warier than [Regard.MOST_WARY], so a draw above that chance
    // needs no look at them.)
    val drive = RAID_PLOT * b.raidWill * days / 7f
    val u = rnd.nextFloat()
    if (dialUse == null && u >= drive * Regard.MOST_WARY) return
    val them = ArrayList<Pair<HexGroup, Regard>>()
    for (id in within) {
        val o = groups.firstOrNull { it.id == id && it.alive } ?: continue
        val r = Regard(b, id, o.band ?: continue)
        if (r.tie <= 0f) them += o to r
    }
    if (them.isEmpty()) return
    val wariest = them.maxOf { it.second.wariness }
    if (drawn(DialSite.RAIDS_PLOT, b.raidWill, u, drive * wariest) { RAID_PLOT * days / 7f * wariest }) {
        // Above all it keeps alive: it falls on the home where it would lose fewest of its own (the defenders as it
        // knows them, at home behind their works; see [raidStrengths]); of homes about as safe (within a step of its
        // score, see [survivalSteps]), the one it is warier of, then the nearest.
        val dying = HashMap<Int, Float>(); val wary = HashMap<Int, Float>()
        for ((o, r) in them) {
            val ob = o.band ?: continue
            val (sg, so) = raidStrengths(g, o, ob, planned = true, atHome = true, weather)
            dying[o.id] = RAID_DEATHS * so / (sg + so); wary[o.id] = r.wariness
        }
        if (dying.isEmpty()) return
        val least = dying.values.min()
        val best = dying.keys.filter { survivalSteps(g, dying.getValue(it) - least) <= 1f }
            .minWith(compareByDescending<Int> { wary.getValue(it) }.thenBy { hexKm(here, b.knownHomes.getValue(it)) }.thenBy { it })
        val at = b.knownHomes.getValue(best)
        b.raid = Raid(at, best, RAID_WEEKS)
        stats.meeting(Met.RAIDS_SET_OUT)
        note(g) { "set out to raid band #%d at hex %d".format(best, at.i) }
    }
}

/** The raids set for this week ([plotRaid]), in the bands' order, each if both bands are still alive and the target still there. */
internal fun HexGroups.fightRaids(weather: Weather) {
    for ((g, target) in bands.raidsDue) {
        val b = g.band ?: continue; val tb = target.band ?: continue
        if (g.alive && target.alive && hw.placeHex[target.place] == hw.placeHex[g.place]) {
            val u = Language.understanding(b.words, tb.words)
            tallyMeeting(MetAs(Regard(b, target.id, tb, u), Regard(tb, g.id, b, u)), Tally.MeetingEnd.RAIDED_HOME)
            fight(g, b, target, tb, planned = true, weather)
        }
    }
    bands.raidsDue.clear()
}

/**
 * Raids: hunger under which a band will go raiding; how far, km; weekly chance a driven band sets
 * out; weeks it keeps at it. Palisades: person-days and timber (kg) for one person's share.
 * Fighting: surprise and the lie of the land.
 */
const val RAID_HUNGER = 0.6f

/**
 * A way's [will] as a share of a whole: [atOne] at the scaffold's 1, and each further unit of will taking the same share of
 * what is left, so more of the same buys ever less and the whole is never reached (no cap: the band finds its own balance).
 */
internal fun shareOfWill(will: Float, atOne: Float) = 1f - (1f - atOne).pow(max(0f, will))

/** A way's [will] as an effect that each further unit adds less to: 1 at the scaffold's 1, about 1.37 at 2, never above 1.58. */
internal fun moreOfWill(will: Float) = (1f - exp(-max(0f, will))) / (1f - exp(-1f))
const val RAID_RANGE_KM = 60.0
const val RAID_PLOT = 0.3f
const val RAID_WEEKS = 4
const val PALISADE_DAYS = 6f
const val PALISADE_WOOD = 80f
const val PALISADE_DEFENCE = 1f
const val SURPRISE_RAID = 1f
const val HIGH_GROUND = 0.5f
