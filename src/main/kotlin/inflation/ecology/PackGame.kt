package inflation.ecology

import inflation.Stream
import inflation.stream
import inflation.world.get
import kotlin.math.floor

// Packs of a kind that also lives settled are game as their settled kind is: to bands, to other packs and to the resident hunters.

/**
 * Fills [HexFauna.packGrown]: the grown of each pack of a kind that also lives settled ([Catalogue.roamingResidents]),
 * per km² of the place it stands at, game to every hunter as its settled kind is; and notes which packs stood where, for
 * the kills ([takePackKills]).
 */
internal fun HexGroups.fillPackGame() {
    for (a in fauna.packGrown) a?.fill(0f)
    val at = HashMap<Long, ArrayList<HexGroup>>()
    for (g in groups) {
        if (!g.alive || g.band != null || g.adults <= 0) continue
        val k = fauna.slots[g.species.index]
        val a = (if (k >= 0) fauna.packGrown[k] else null) ?: continue
        a[g.place.i] += g.adults / land.areaKm2[g.place]
        at.getOrPut(g.place.i.toLong() * 64 + k) { ArrayList() } += g
    }
    packsAt = at
}

/**
 * Takes the pack members killed as game ([HexFauna.packKills]) off the packs that stood where they were killed (as
 * [fillPackGame] last found them), each kill one grown of a pack drawn by its numbers; a place's kills of a kind summed
 * first, a part of one a kill or not by chance. Kills by the resident hunters are counted among their kind's deaths
 * here; kills by the groups were counted as they made them.
 */
internal fun HexGroups.takePackKills(day: Long) {
    val q = fauna.packKills
    if (q.isEmpty()) return
    val all = ArrayList<PackKill>(q.size)
    while (true) all += q.poll() ?: break
    // (In a fixed order, whatever order the threads made them in.)
    all.sortWith(compareBy<PackKill>({ it.at }, { it.k }, { it.by }))
    val at = packsAt ?: return
    var i = 0
    while (i < all.size) {
        val p = all[i].at; val k = all[i].k
        var heads = 0f; var byResidents = 0f
        while (i < all.size && all[i].at == p && all[i].k == k) { heads += all[i].heads; if (all[i].by < 0) byResidents += all[i].heads; i++ }
        val packs = at[p.toLong() * 64 + k] ?: continue
        val rnd = stream(seedBase, Stream.PACK_KILLS, p.toLong() * 64 + k, day)
        var n = floor(heads).toInt() + if (rnd.nextFloat() < heads - floor(heads)) 1 else 0
        var dead = 0
        while (n-- > 0) {
            var left = 0; for (g in packs) if (g.alive) left += g.adults
            if (left <= 0) break
            var pick = rnd.nextInt(left)
            for (g in packs) {
                if (!g.alive) continue
                if (pick < g.adults) { adultsDied(g, 1, rnd); break }
                pick -= g.adults
            }
            dead++
        }
        val byRes = if (heads > 0f) Math.round(dead * byResidents / heads) else 0
        if (byRes > 0) stats.deaths.merge(Tally.of(fauna.kinds[k], DeathCause.RESIDENT_HUNTERS), byRes, Int::plus)
    }
}
