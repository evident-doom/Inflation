package inflation.learning

import inflation.ecology.HexGroup
import kotlin.math.max
import kotlin.math.min

/**
 * What became of the members of every group in a training world, week by week, so that a choice is
 * judged by the fate of those who made it: their own births and deaths count; members a group takes in
 * from others that join it do not, and once a group joins another its members fare as that one does.
 * Offshoots take their share of a group's members with them. For bands, the luck in their own births
 * and deaths ([HexGroup.luck]) is taken out, so a choice is judged by what it made likely, not by chance.
 * Each week's foreseen survival of a band ([HexGroup.foreseen]) is kept too, for the members' share foreseen alive ([own]).
 * The weeks no choice still waiting needs are let go, so a world can live on for ever.
 */
class Outcomes {
    private class Track(var base: Int) {
        var size = IntArray(64); var gain = IntArray(64); var luck = FloatArray(64) { Float.NaN }; var fore = FloatArray(64) { 1f }; var n = 0
        private fun grow(i: Int) {
            if (i >= size.size) { val m = max(2 * size.size, i + 1); size = size.copyOf(m); gain = gain.copyOf(m); val old = luck.size; luck = luck.copyOf(m); fore = fore.copyOf(m); for (k in old until m) { luck[k] = Float.NaN; fore[k] = 1f } }
        }
        fun set(w: Int, s: Int, l: Float, f: Float) { val i = w - base; if (i < 0) return; grow(i); size[i] = s; luck[i] = l; fore[i] = f; n = max(n, i + 1) }
        fun took(w: Int, members: Int) { val i = w - base; if (i < 0) return; grow(i); gain[i] += members; n = max(n, i + 1) }
        fun size(w: Int) = (w - base).let { if (it in 0 until n) size[it] else 0 }
        fun gain(w: Int) = (w - base).let { if (it in 0 until n) gain[it] else 0 }
        fun fore(w: Int) = (w - base).let { if (it in 0 until n) fore[it] else 1f }
        // (As last seen, once it is gone.)
        fun luck(w: Int): Float { var k = min(w - base, n - 1); while (k > 0 && luck[k].isNaN()) k--; return if (k < 0 || luck[k].isNaN()) 0f else luck[k] }
        fun cut(w: Int) {
            val d = w - base; if (d <= 0) return
            val keep = luck(w - 1)
            if (d >= n) { n = 0; size.fill(0); gain.fill(0); luck.fill(Float.NaN); fore.fill(1f) } else { size = size.copyOfRange(d, size.size); gain = gain.copyOfRange(d, gain.size); luck = luck.copyOfRange(d, luck.size); fore = fore.copyOfRange(d, fore.size); n -= d; if (luck[0].isNaN()) luck[0] = keep }
            base = w
        }
    }

    private class Join(val into: Int, val week: Int, val members: Int)

    private val tracks = HashMap<Int, Track>()
    private val born = HashMap<Int, Int>()
    private val children = HashMap<Int, MutableList<Int>>()
    private val joined = HashMap<Int, Join>()

    /** Week [week]'s numbers: [groups] (the world's list) and [merged], those that joined another this week. */
    fun record(week: Int, groups: Collection<HexGroup>, merged: Collection<HexGroup>) {
        for (g in groups) {
            if (g.parent >= 0 && g.id !in born) { born[g.id] = week; children.getOrPut(g.parent) { ArrayList() } += g.id }
            val tr = tracks.getOrPut(g.id) { Track(week) }
            tr.set(week, if (g.alive) g.size else 0, if (g.species.human) g.luck else 0f, if (g.species.human && g.alive) g.foreseen else 1f)
        }
        for (g in merged) {
            if (g.id in joined || g.mergedSize < 0) continue
            if (g.mergedInto < 0) continue
            joined[g.id] = Join(g.mergedInto, week, g.mergedSize)
            tracks.getOrPut(g.mergedInto) { Track(week) }.took(week, g.mergedSize)
        }
    }

    /**
     * How the members of group [id] at week [from] fare through week [from] + [weeks]: their numbers at
     * each of those weeks (index 0 for week from+1); and into [foreseen] (if given), of those numbers, the
     * share each group they are then in foresees alive a year ahead, summed ([HexGroup.foreseen]).
     */
    fun own(id: Int, from: Int, weeks: Int, foreseen: FloatArray? = null): FloatArray {
        val out = FloatArray(weeks)
        follow(id, from, tracks[id]?.size(from)?.toFloat() ?: 0f, from + weeks, out, from, 0, foreseen)
        return out
    }

    /** Group [id]'s foreseen survival as it stood in week [w] (1 if not a band, or not seen then). */
    fun foreseen(id: Int, w: Int): Float = tracks[id]?.fore(w) ?: 1f

    private fun follow(id0: Int, from: Int, start: Float, to: Int, out: FloatArray, base: Int, depth: Int, foreseen: FloatArray?) {
        var id = id0; var own = start
        for (w in from + 1..to) {
            if (own <= 0f) return
            val tr = tracks[id] ?: return
            val before = tr.size(w - 1)
            if (before <= 0) return
            val j = joined[id]
            if (j != null && j.week == w) {
                // (Those lost this week before joining are lost; the rest go on with the group they joined.)
                own *= j.members.toFloat() / before
                id = j.into
            } else {
                if (depth < 3) for (c in children[id].orEmpty()) if (born[c] == w) {
                    val members = tracks[c]?.size(w) ?: 0
                    if (members <= 0) continue
                    val share = own / before * members
                    out[w - base - 1] += share
                    if (foreseen != null) foreseen[w - base - 1] += share * (tracks[c]?.fore(w) ?: 1f)
                    follow(c, w, share, to, out, base, depth + 1, foreseen)
                }
                // Its own births and deaths, less those it took in and, for bands, less their luck this week.
                val kept = tr.size(w) - tr.gain(w) - (tr.luck(w) - tr.luck(w - 1))
                own *= max(0f, kept) / before
            }
            out[w - base - 1] += own
            if (foreseen != null) foreseen[w - base - 1] += own * (tracks[id]?.fore(w) ?: 1f)
        }
    }

    /** Lets go of the weeks before [oldest], and of the groups gone before it that are not in [here]. */
    fun cut(oldest: Int, here: Set<Int>) {
        val it = tracks.entries.iterator()
        while (it.hasNext()) { val (id, tr) = it.next(); if (id !in here && tr.base + tr.n <= oldest) it.remove() else tr.cut(oldest) }
        joined.keys.retainAll(tracks.keys)
        born.keys.retainAll(tracks.keys)
        children.keys.retainAll(tracks.keys)
    }
}
