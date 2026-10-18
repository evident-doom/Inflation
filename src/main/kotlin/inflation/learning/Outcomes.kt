package inflation.learning

import inflation.ecology.HexGroup
import inflation.ecology.SCATTERED
import kotlin.math.max
import kotlin.math.min

/**
 * What became of the members of every group in a training world, week by week, so that a choice is
 * judged by the fate of those who made it: their own births and deaths count; members a group takes in
 * from others that join it do not, and once a group joins another its members fare as that one does.
 * Offshoots take their share of a group's members with them; a herd that scatters into the land counts as living on. For bands, the luck in their own births
 * and deaths ([HexGroup.luck]) is taken out, so a choice is judged by what it made likely, not by chance.
 * Where each group stands is kept too, so a choice can also be judged by how far its members went ([where]).
 * The weeks no choice still waiting needs are let go, so a world can live on for ever.
 */
class Outcomes {
    private class Track(var base: Int) {
        var size = IntArray(64); var gain = IntArray(64); var luck = FloatArray(64) { Float.NaN }; var n = 0
        // (The hex it stood on, plus one: 0 where it was not seen.)
        var at = IntArray(64)
        private fun grow(i: Int) {
            if (i >= size.size) { val m = max(2 * size.size, i + 1); size = size.copyOf(m); gain = gain.copyOf(m); at = at.copyOf(m); val old = luck.size; luck = luck.copyOf(m); for (k in old until m) luck[k] = Float.NaN }
        }
        fun set(w: Int, s: Int, l: Float, hex: Int) { val i = w - base; if (i < 0) return; grow(i); size[i] = s; luck[i] = l; at[i] = hex + 1; n = max(n, i + 1) }
        fun took(w: Int, members: Int) { val i = w - base; if (i < 0) return; grow(i); gain[i] += members; n = max(n, i + 1) }
        fun size(w: Int) = (w - base).let { if (it in 0 until n) size[it] else 0 }
        fun gain(w: Int) = (w - base).let { if (it in 0 until n) gain[it] else 0 }
        // (As last seen, once it is gone.)
        fun luck(w: Int): Float { var k = min(w - base, n - 1); while (k > 0 && luck[k].isNaN()) k--; return if (k < 0 || luck[k].isNaN()) 0f else luck[k] }
        // (As last seen, at or before week [w]; -1 if never.)
        fun at(w: Int): Int { var k = min(w - base, n - 1); while (k > 0 && at[k] == 0) k--; return if (k < 0) -1 else at[k] - 1 }
        fun cut(w: Int) {
            val d = w - base; if (d <= 0) return
            val keep = luck(w - 1)
            val keepAt = at(w - 1)
            if (d >= n) { n = 0; size.fill(0); gain.fill(0); luck.fill(Float.NaN); at.fill(0) } else { size = size.copyOfRange(d, size.size); gain = gain.copyOfRange(d, gain.size); at = at.copyOfRange(d, at.size); if (at[0] == 0) at[0] = keepAt + 1; luck = luck.copyOfRange(d, luck.size); n -= d; if (luck[0].isNaN()) luck[0] = keep }
            base = w
        }
    }

    private class Join(val into: Int, val week: Int, val members: Int, val hex: Int)

    private val tracks = HashMap<Int, Track>()
    private val born = HashMap<Int, Int>()
    private val children = HashMap<Int, MutableList<Int>>()
    private val joined = HashMap<Int, Join>()

    /** Week [week]'s numbers: [groups] (the world's list) and [merged], those that joined another this week; [hexOf] where a group stands. */
    fun record(week: Int, groups: Collection<HexGroup>, merged: Collection<HexGroup>, hexOf: (HexGroup) -> Int = { -1 }) {
        for (g in groups) {
            if (g.parent >= 0 && g.id !in born) { born[g.id] = week; children.getOrPut(g.parent) { ArrayList() } += g.id }
            tracks.getOrPut(g.id) { Track(week) }.set(week, if (g.alive) g.size else 0, if (g.species.human) g.luck else 0f, hexOf(g))
        }
        for (g in merged) {
            if (g.id in joined || g.mergedSize < 0) continue
            if (g.mergedInto == SCATTERED) { joined[g.id] = Join(SCATTERED, week, g.mergedSize, hexOf(g)); continue }
            if (g.mergedInto < 0) continue
            joined[g.id] = Join(g.mergedInto, week, g.mergedSize, hexOf(g))
            tracks.getOrPut(g.mergedInto) { Track(week) }.took(week, g.mergedSize)
        }
    }

    /**
     * How the members of group [id] at week [from] fare through week [from] + [weeks]: their numbers at
     * each of those weeks (index 0 for week from+1).
     */
    fun own(id: Int, from: Int, weeks: Int): FloatArray {
        val out = FloatArray(weeks)
        follow(id, from, tracks[id]?.size(from)?.toFloat() ?: 0f, from + weeks, out, from, 0)
        return out
    }

    private fun follow(id0: Int, from: Int, start: Float, to: Int, out: FloatArray, base: Int, depth: Int) {
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
                // (A herd that scattered into the land: its members are no longer followed one by one, and are counted
                // as living on to the end, the residents of a kind dying far slower than a half-year's horizon shows.)
                if (j.into == SCATTERED) { for (v in w..to) out[v - base - 1] += own; return }
                id = j.into
            } else {
                if (depth < 3) for (c in children[id].orEmpty()) if (born[c] == w) {
                    val members = tracks[c]?.size(w) ?: 0
                    if (members <= 0) continue
                    val share = own / before * members
                    out[w - base - 1] += share
                    follow(c, w, share, to, out, base, depth + 1)
                }
                // Its own births and deaths, less those it took in and, for bands, less their luck this week.
                val kept = tr.size(w) - tr.gain(w) - (tr.luck(w) - tr.luck(w - 1))
                own *= max(0f, kept) / before
            }
            out[w - base - 1] += own
        }
    }

    /**
     * Where the members of group [id] stood at week [to]: with the group they joined, if they joined one; where they
     * spread out, if their herd scattered; else where their group was last seen. -1 if it never was.
     */
    fun where(id: Int, to: Int): Int {
        var g = id
        repeat(64) {
            val j = joined[g]
            if (j != null && j.week <= to) { if (j.into == SCATTERED) return j.hex; g = j.into }
            else return tracks[g]?.at(to) ?: -1
        }
        return -1
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
