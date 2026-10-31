package inflation.ecology

import inflation.Kg
import inflation.ecology.HexSmallLife.DeadPool
import inflation.world.Place
import kotlin.math.max
import kotlin.math.min

/*
 * Hunters of other kinds, residents and packs alike (Olli 2026-10-31: packs and residents by the same rules). When a pack meets
 * a much stronger party of another kind, the stronger now and then kills one of its young, or seldom one of its grown
 * ([enemiesMeet]). The resident hunters meet the parties standing where they are, and the stronger resident hunters about
 * them, at the same rate ([encounters]), slip away as often ([slipChance]) and are killed at the same odds; packs meet the
 * much stronger resident hunters where they end their week the same way.
 */

/** Heads in one resident band of kind [s]: the band [residentStrength] reckons, as it goes about. */
private fun residentBand(s: Species) = (if (s.foragingParty > 0) minOf(s.foragingParty, s.groupSize) else s.groupSize).toFloat()

/**
 * For each resident hunter kind and place, the shares of its grown and young the much stronger hunters of other kinds
 * about it would kill in a week, and the kind that would kill the most of them ([HexFauna.guildAdult], [HexFauna.guildYoung],
 * [HexFauna.guildBy]): the parties standing in each place at the week's start (packs and bands; people kill grown hunters,
 * never seek out their young) and the stronger resident hunters there. The residents die by them next week ([HexFauna.hazards]).
 */
internal fun HexGroups.fillGuildOdds() {
    val f = fauna; val days = this.days.toFloat()
    val hunters = f.kinds.indices.filter { f.kinds[it].carnivore }
    val most = FloatArray(n)
    for (k in hunters) { f.guildAdult[k]!!.fill(0f); f.guildYoung[k]!!.fill(0f); f.guildBy[k]!!.fill(-1) }
    fun meet(k: Int, p: Int, strong: Species, meetings: Float, will: Float, best: FloatArray) {
        val w = f.kinds[k]; val a = f.adults[k][p]; val y = f.young[k][p]
        if (!(meetings > 0f) || a + y <= 0f) return
        val odds = shelteredFromKilling(w, Place(p)) * will * (1f - slipChance(w, strong, Place(p)))
        // (Each meeting kills one of the band's young, else now and then one of its grown, as [enemiesMeet] does.)
        val band = residentBand(w)
        val youngKill = if (strong.human || y <= 0f) 0f else min(1f, INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds)
        val grownKill = (1f - youngKill) * INTRAGUILD_KILL * odds
        val youngShare = meetings * youngKill / max(1f, band * y / (a + y))
        val grownShare = meetings * grownKill / max(1f, band * a / (a + y))
        f.guildYoung[k]!![p] += youngShare; f.guildAdult[k]!![p] += grownShare
        val by = youngShare * y + grownShare * a
        if (by > best[p]) { best[p] = by; f.guildBy[k]!![p] = strong.index }
    }
    for (k in hunters) {
        val w = f.kinds[k]; val mine = HOME_ADVANTAGE * residentStrength(w)
        most.fill(0f)
        // The parties standing in each place (at home there the stronger).
        for (g in groups) {
            if (!g.alive) continue
            val p = g.place.i
            val st = strength(g) * (if (g.home == hw.placeHex[g.place]) HOME_ADVANTAGE else 1f)
            if (st > 2f * mine) meet(k, p, g.species, encounters(w, g.species, false, g.place, days), g.will[AnimalWay.KILL], most)
        }
        // The stronger resident hunters of other kinds there, band by band.
        for (s in hunters) {
            val o = f.kinds[s]
            if (s == k || residentStrength(o) <= 2f * residentStrength(w)) continue
            for (p in 0 until n) {
                val bands = (f.adults[s][p] + f.young[s][p]) * land.areaKm2[p] / o.groupSize
                if (bands > 0f) meet(k, p, o, encounters(w, o, false, Place(p), days) * bands, 1f, most)
            }
        }
    }
}

/**
 * Packs meet the much stronger resident hunters of other kinds where they end their week, by the rule they meet stronger
 * packs by ([enemiesMeet]): they slip away or not, and now and then one of their young, else seldom one of their grown,
 * is killed.
 */
internal fun HexGroups.residentsMeetPacks() {
    val f = fauna; val days = this.days.toFloat()
    for (g in groups) {
        if (!g.alive || g.band != null || g.species.human) continue
        val p = g.place
        val mine = strength(g) * (if (g.home == hw.placeHex[p]) HOME_ADVANTAGE else 1f)
        for (s in f.kinds.indices) {
            val o = f.kinds[s]
            if (!o.carnivore || o == g.species || HOME_ADVANTAGE * residentStrength(o) <= 2f * mine) continue
            val bands = (f.adults[s][p.i] + f.young[s][p.i]) * land.areaKm2[p.i] / o.groupSize
            if (!(bands > 0f)) continue
            repeat(poisson(encounters(g.species, o, false, p, days) * bands, rnd)) {
                if (!g.alive || rnd.nextFloat() < slipChance(g.species, o, p)) return@repeat
                stats.meeting(Met.HUNTERS_OF_OTHER_KINDS)
                val odds = shelteredFromKilling(g.species, p)
                if (g.young > 0 && rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds) {
                    g.young--
                    small.deposit(DeadPool.CARRION, p, Kg(HexFauna.YOUNG_WEIGHT * g.species.massKg))
                    stats.deaths.merge(Tally.of(g.species, ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, o)), 1, Int::plus)
                } else if (g.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * odds) {
                    adultsDied(g, 1, rnd)
                    small.deposit(DeadPool.CARRION, p, Kg(g.species.massKg))
                    stats.deaths.merge(Tally.of(g.species, ByBeast.of(ByBeast.How.KILLED_BY, o)), 1, Int::plus)
                }
            }
        }
    }
}
