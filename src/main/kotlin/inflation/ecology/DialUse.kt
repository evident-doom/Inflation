package inflation.ecology

import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder
import kotlin.math.abs

/**
 * Where a band's ways ([setWays]' outputs: its efforts on each food, its store aim, its ranking of the tools and its
 * [BandWay]s) are read in the model, one entry per place in the code that reads one. [dial] is the way's output slot
 * ([WAYS_OUT_NAMES]), or -1 where the site reads several (an effort by food, the tools' ranking), given when counted.
 */
enum class DialSite(val label: String, val dial: Int) {
    EFFORT_RATE("foraging: how often it meets the food", -1),
    HUNTERS_SHARE("foraging: share of its hands hunting", Food.BIG_GAME.ordinal),
    STORE_FEAST("place choice: larder above aim draws to a feast", STORE_AIM_SLOT),
    STORE_HOST("feast: what the host can spare above aim", STORE_AIM_SLOT),
    STORE_WANT("stores: food worked to lay by", STORE_AIM_SLOT),
    STORE_HOUSES("houses: larder enough to build", STORE_AIM_SLOT),
    MAKE_FIRST("tools: order of making", -1),
    SCOUTS_HANDS("foraging: hands kept back as scouts", BandWay.SCOUTS.slot),
    SCOUTS_SEEN("raids: lookouts see the raiders coming", BandWay.SCOUTS.slot),
    SCOUTS_GUARD("beasts: lookouts guard the camp", BandWay.SCOUTS.slot),
    SCOUTS_TOLD("country: scouts bring word of it", BandWay.SCOUTS.slot),
    SCOUTS_FIND_CACHE("caches: chance of finding another's", BandWay.SCOUTS.slot),
    HOUSES("houses: weeks at camp before building", BandWay.HOUSES.slot),
    RAIDS_MEETING("raids: a meeting comes to blows", BandWay.RAIDS.slot),
    RAIDS_HUNGER("raids: hunger enough to plot one", BandWay.RAIDS.slot),
    RAIDS_PLOT("raids: plotting one", BandWay.RAIDS.slot),
    PALISADES("palisades: raided lately enough to build", BandWay.PALISADES.slot),
    SOWING("sowing: grain sown", BandWay.SOWING.slot),
    GATHER_REACH("foraging: ground the gatherers cover", BandWay.GATHERING_RANGE.slot),
    GATHER_RING("foraging: the ring round camp", BandWay.GATHERING_RANGE.slot),
    GATHER_WALK("going: walking out further", BandWay.GATHERING_RANGE.slot),
    GATHER_MISHAPS("wounds: mishaps at work", BandWay.GATHERING_RANGE.slot),
    GATHER_SPREAD("beasts: gatherers spread thin", BandWay.GATHERING_RANGE.slot),
    CHILDREN_HANDS("foraging: hands kept by the children", BandWay.WATCHING_CHILDREN.slot),
    CHILDREN_TAKEN("beasts: children taken", BandWay.WATCHING_CHILDREN.slot),
    CHILDREN_PLAN("place choice: children taken, foreseen", BandWay.WATCHING_CHILDREN.slot),
    NIGHT_FIRE_WOOD("fire: wood burnt through the night", BandWay.NIGHT_FIRE.slot),
    NIGHT_FIRE_GUARD("beasts: the night fire guards", BandWay.NIGHT_FIRE.slot),
    SHUN_SICK("meetings: keeping away from the sick", BandWay.SHUNNING_THE_SICK.slot),
    EXPLORE_SCORE("place choice (hand-written score): strange country", BandWay.EXPLORING.slot),
    EXPLORE_MOVE("place choice: the place drawn", BandWay.EXPLORING.slot),
    GIVE_VISIT("kin visits: share given", BandWay.GIVING.slot),
    GIVE_MEETING("meetings: food given to the hungry", BandWay.GIVING.slot),
    GIVE_ORNAMENTS("meetings: ornaments given", BandWay.GIVING.slot),
    GIVE_SPARE("meetings: spare food given", BandWay.GIVING.slot),
    ROB_TAKE("caches: takes from one it found", BandWay.TAKING_FROM_CACHES.slot),
    ROB_SHARE("caches: share it takes", BandWay.TAKING_FROM_CACHES.slot),
    CACHING("caches: what it leaves cached", BandWay.CACHING.slot),
    DRYING("stores: fresh meat dried", BandWay.DRYING.slot),
    KEEP_CAPTURE("animals: young caught kept", BandWay.KEEPING_YOUNG_ANIMALS.slot),
    KEEP_TAME("animals: young that follow kept", BandWay.KEEPING_YOUNG_ANIMALS.slot),
    SHELTERS("camp: shelters put up", BandWay.SHELTERS.slot),
    FEASTING("feasts: keen to hold one", BandWay.FEASTING.slot),
    APPOINTMENTS("appointments: making one", BandWay.APPOINTMENTS.slot),
    BURNING_OFF("fire: burning off dry grass", BandWay.BURNING_OFF.slot),
    REPAYING("favours: returned when due", BandWay.REPAYING.slot),
    MENDING_FIRST("stores: held back to mend first", BandWay.MENDING_FIRST.slot),
    STORING("stores: what each food is weighed at for laying by", -1),
}

/**
 * dialUse=FILE: how the bands' ways act in the model ([DialSite]), counted over a run. At each place in the code that reads a
 * way, for the band reading it: that it was read ([reached]), that its value was away from the scaffold's 1 ([away]), that
 * what came of it differs from what the scaffold's 1 would have given ([changed]: a chance that came out otherwise, an amount
 * or order that differs), the mean size of that difference against the larger of the two ([shift], 0..1), and that some of its value went
 * unused for a cap or a floor ([capped]). Only counts: the model runs as it would without it, and draws nothing more.
 */
class DialUse {
    private val n = DialSite.entries.size * WAYS_OUT
    private val reached = Array(n) { LongAdder() }
    private val away = Array(n) { LongAdder() }
    private val changed = Array(n) { LongAdder() }
    private val capped = Array(n) { LongAdder() }
    private val shift = Array(n) { DoubleAdder() }
    /** Band-weeks counted (each band's week of foraging), to scale the counts. */
    val bandWeeks = LongAdder()

    private fun at(site: DialSite, dial: Int) = site.ordinal * WAYS_OUT + (if (site.dial >= 0) site.dial else dial)

    /** An amount at [site] that came to [value] with the way at [will], and would have been [at1] at the scaffold's 1; [cut] if a cap or floor took some of the way off. */
    fun amount(site: DialSite, will: Float, value: Float, at1: Float, cut: Boolean = false, dial: Int = -1) {
        val i = at(site, dial)
        reached[i].increment()
        if (abs(will - 1f) > AWAY) away[i].increment()
        val d = abs(value - at1)
        val scale = maxOf(abs(at1), abs(value), 1e-6f)
        if (d > SAME * scale) { changed[i].increment(); shift[i].add((d / scale).toDouble()) }
        if (cut) capped[i].increment()
    }

    /** A chance at [site]: drawn [u], it happens below [p] (the way at [will]) and would have below [p1] (at the scaffold's 1). */
    fun chance(site: DialSite, will: Float, u: Float, p: Float, p1: Float, cut: Boolean = false, dial: Int = -1) {
        val i = at(site, dial)
        reached[i].increment()
        if (abs(will - 1f) > AWAY) away[i].increment()
        if ((u < p) != (u < p1)) { changed[i].increment(); shift[i].add((abs(p - p1) / maxOf(abs(p), abs(p1), 1e-6f)).toDouble()) }
        if (cut) capped[i].increment()
    }

    /** A yes or no at [site] that came out [yes] with the way at [will], and [yes1] at the scaffold's 1. */
    fun choice(site: DialSite, will: Float, yes: Boolean, yes1: Boolean, cut: Boolean = false, dial: Int = -1) {
        val i = at(site, dial)
        reached[i].increment()
        if (abs(will - 1f) > AWAY) away[i].increment()
        if (yes != yes1) changed[i].increment()
        if (cut) capped[i].increment()
    }

    /** The counts, a line per site and way read there, then a line per way over all its sites; per band-week. */
    fun report(names: List<String>): String {
        val weeks = bandWeeks.sum().coerceAtLeast(1L).toDouble()
        val sb = StringBuilder()
        sb.append("dialUse: how the bands' ways acted, over %d band-weeks (reads per band-week; of the reads, the share away from 1, ".format(bandWeeks.sum()))
        sb.append("the share where the outcome differs from the scaffold's 1, the mean size of the difference where it does, against the larger of the two, the share capped)\n")
        sb.append("%-24s %-52s %10s %7s %8s %8s %7s\n".format("way", "site", "reads/bw", "away", "changed", "size", "capped"))
        fun line(way: String, site: String, r: Long, a: Long, c: Long, s: Double, k: Long) {
            sb.append("%-24s %-52s %10.3f %6.1f%% %7.1f%% %8.3f %6.1f%%\n".format(way, site, r / weeks,
                pct(a, r), pct(c, r), if (c > 0) s / c else 0.0, pct(k, r)))
        }
        for (site in DialSite.entries) for (d in 0 until WAYS_OUT) {
            val i = site.ordinal * WAYS_OUT + d
            val r = reached[i].sum(); if (r == 0L) continue
            line(names.getOrElse(d) { "way $d" }, site.label, r, away[i].sum(), changed[i].sum(), shift[i].sum(), capped[i].sum())
        }
        sb.append("by way, over all its sites (ways never read are listed with 0):\n")
        for (d in 0 until WAYS_OUT) {
            var r = 0L; var a = 0L; var c = 0L; var s = 0.0; var k = 0L
            for (site in DialSite.entries) { val i = site.ordinal * WAYS_OUT + d; r += reached[i].sum(); a += away[i].sum(); c += changed[i].sum(); s += shift[i].sum(); k += capped[i].sum() }
            line(names.getOrElse(d) { "way $d" }, "(all)", r, a, c, s, k)
        }
        return sb.toString()
    }

    private fun pct(x: Long, of: Long) = if (of > 0) 100.0 * x / of else 0.0

    private companion object {
        /** A way this close to 1 counts as the scaffold's. */
        const val AWAY = 1e-3f
        /** Amounts this close (relatively) count as the same. */
        const val SAME = 1e-4f
    }
}

/**
 * Whether a chance [p] comes up for the draw [u] (`u < p`), counting it in [HexGroups.dialUse] as the chance at [site] of a way at
 * [will], against [p1] at the scaffold's 1 (worked out only when counting).
 */
internal inline fun HexGroups.drawn(site: DialSite, will: Float, u: Float, p: Float, cut: Boolean = false, p1: () -> Float): Boolean {
    dialUse?.chance(site, will, u, p, p1(), cut)
    return u < p
}
