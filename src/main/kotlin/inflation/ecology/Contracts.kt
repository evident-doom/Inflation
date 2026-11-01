package inflation.ecology

import kotlin.math.max
import kotlin.math.min

/*
 * What binds animals to the band that keeps them. A contract is a pattern of behaviour the engine writes down, never one it
 * forces: each side is a group of its own, and stays while staying serves it. A band's kept animals are held in one part
 * per contract ([Herd], [Dogs]), which carries its own rules for when the band splits or another joins it. They stand where
 * their keeper camps: no place of their own until there are excursions.
 */

/** The kinds of bond between a band and the animals it keeps. */
enum class ContractKind(val label: String) {
    /**
     * Kept herds. The keepers choose which are killed and which young are kept, lead them to grass and milk the mothers; the
     * animals' own share is keeping together by the camp, fed and safe. One-sided: the herd's only way out is to stray.
     */
    HERDING("herding"),
    /** Dogs. They live on the camp's scraps and a little of its food, and hunt, guard and bark with it; either side may leave. */
    COMPANION("companions"),
}

/**
 * The herd a band keeps, by kind of the residents ([HexFauna.kinds] index), under [ContractKind.HERDING]: young taken in the
 * hunt and raised, and their offspring. (Saves from before it was split out of the band kept these fields on the band.)
 */
class Herd(kinds: Int) {
    val contract get() = ContractKind.HERDING
    /** The grown, of each kind. */
    val herd = FloatArray(kinds)
    /** How tame each kind has grown under the band, 0..1: the docile breed and stay, the wild ones run off. */
    val herdTame = FloatArray(kinds)
    /**
     * Of each kind, whether its herd kept its increase to grow at its last week's keeping ([keepHerds]), and so fed the band
     * only its milk. (Empty in a band loaded from a save made before it; [keepHerds] sizes it to [herd].)
     */
    var herdGrew = BooleanArray(kinds)
    /**
     * Of each kind, the years left of the time its herd, begun from young caught in the hunt, is kept as pets: small animals
     * kept for their own sake, as companions, and not yet weighed as food ([keepHerds]) till the young it was begun with have
     * grown, or sooner for a kind of bad temper ([petYears]). 0 for a herd kept for food. (Empty in a band loaded from a save made before it;
     * [keepHerds] sizes it to [herd].)
     */
    var herdPet = FloatArray(kinds)
    /**
     * Of each kind, its kept young not yet grown, born in its herd or caught in the hunt: each weighs [HexFauna.YOUNG_WEIGHT]
     * of a grown one, eats about half as much, and grows up over its kind's years to maturity, as the residents' young do
     * ([keepHerds]). [herd] holds the grown. (Empty in a band loaded from a save made before it; [youngHerd] sizes it.)
     */
    var herdYoung = FloatArray(kinds)
    /**
     * Of each kind, the share of its grown that are females: half among the young as they grow up, more as the keepers eat
     * the males first ([keepHerds]); the mothers are among them ([herdMothers]). (Empty in a band loaded from a save made
     * before it; [sows] sizes it, at half.)
     */
    var herdSows = FloatArray(kinds) { 0.5f }
    /** [herdSows], sized to [herd]. */
    fun sows(): FloatArray { if (herdSows.size != herd.size) herdSows = FloatArray(herd.size) { 0.5f }; return herdSows }
    /** [herdYoung], sized to [herd]. */
    fun youngHerd(): FloatArray { if (herdYoung.size != herd.size) herdYoung = FloatArray(herd.size); return herdYoung }
    /** The heads of kind [k] kept, grown and young. */
    fun heads(k: Int) = herd[k] + youngHerd().getOrElse(k) { 0f }
    /** Whether it keeps any animals, grown or young. */
    fun keepsAnimals() = herd.any { it > 0f } || herdYoung.any { it > 0f }
    /** Of each kind, the heads beasts of prey took from its herd since it last kept it ([raidHerd], [keepHerds]). */
    var herdTaken = FloatArray(kinds)
    /**
     * For the census only: heads beasts of prey took from its herds since the last census, all kinds together, and the
     * herd it kept over that time, in head-years (heads times the step's share of a year, summed over the steps).
     */
    @Transient var takenSince = 0f
    @Transient var heldSince = 0f

    /** A new band [o] splitting off its keeper takes [share] of the herd, grown and young, and the herd's tameness, pets and last week's keeping. */
    fun splitInto(o: Herd, share: Float) {
        sows().copyInto(o.sows())
        herdTame.copyInto(o.herdTame); herd.copyInto(o.herd); herdGrew.copyInto(o.herdGrew); herdPet.copyInto(o.herdPet)
        // (A band from a save made when there were fewer resident kinds keeps fewer: see [HexFauna.kinds].)
        for (i in 0 until min(herd.size, o.herd.size)) { o.herd[i] *= share; herd[i] -= o.herd[i] }
        run { val y = youngHerd(); val oy = o.youngHerd(); for (i in 0 until min(y.size, oy.size)) { oy[i] = y[i] * share; y[i] -= oy[i] } }
    }

    /** The herd of a band joining its keeper, [b], is added to it, tameness blended by the animals. */
    fun absorb(b: Herd) {
        val a = this
        val ay = a.youngHerd(); val by = b.youngHerd()
        for (i in 0 until min(a.herd.size, b.herd.size)) {
            val n = a.herd[i] + b.herd[i]
            val heads = n + ay[i] + by[i]
            if (heads > 0f) a.herdTame[i] = ((a.herd[i] + ay[i]) * a.herdTame[i] + (b.herd[i] + by[i]) * b.herdTame[i]) / heads
            if (n > 0f) { val sa = a.sows(); val sb = b.sows(); sa[i] = (a.herd[i] * sa[i] + b.herd[i] * sb[i]) / n }
            a.herd[i] = n; ay[i] += by[i]
            // (Pets while either band's were: the joined herd is weighed as food once the younger has grown.)
            if (i < a.herdPet.size && i < b.herdPet.size) a.herdPet[i] = max(a.herdPet[i], b.herdPet[i])
        }
    }

    companion object {
        /** Each field's rule, as [splitInto] and [absorb] carry it out; BandCarryTest fails when a field is missing here. */
        val CARRY: Map<String, Pair<OnSplit, OnJoin>> by lazy {
            LinkedHashMap<String, Pair<OnSplit, OnJoin>>().apply {
                put("herd", OnSplit.SHARED to OnJoin.SUM); put("herdYoung", OnSplit.SHARED to OnJoin.SUM)
                put("herdPet", OnSplit.COPIED to OnJoin.MAX)
                put("herdTame", OnSplit.COPIED to OnJoin.BLENDED); put("herdSows", OnSplit.COPIED to OnJoin.BLENDED)
                put("herdGrew", OnSplit.COPIED to OnJoin.LOST)
                put("herdTaken", OnSplit.FRESH to OnJoin.LOST)
            }
        }
    }
}

/**
 * The dogs a band keeps, by kind ([Species.index]), under [ContractKind.COMPANION]: wolf pups taken from bold packs become
 * dogs. (Saves from before it was split out of the band kept these fields on the band.)
 */
class Dogs(kinds: Int) {
    val contract get() = ContractKind.COMPANION
    /** Its dogs, by kind. */
    val kept = FloatArray(kinds)
    /** Its dogs against its hunters (one to three a full pack): they track, bay and hold small game, and bark at night. */
    var dogShare = 0f
    /** How far its dogs were fed this week, 0..1 (1 with none; for the census). */
    @Transient var dogFed = 1f

    /** A new band [o] splitting off its keeper takes [share] of the dogs. */
    fun splitInto(o: Dogs, share: Float) {
        kept.copyInto(o.kept)
        for (i in o.kept.indices) { o.kept[i] *= share; kept[i] -= o.kept[i] }
    }

    /** The dogs of a band joining its keeper, [b], are added to its own. */
    fun absorb(b: Dogs) { for (i in b.kept.indices) kept[i] += b.kept[i] }

    companion object {
        /** Each field's rule, as [splitInto] and [absorb] carry it out; BandCarryTest fails when a field is missing here. */
        val CARRY: Map<String, Pair<OnSplit, OnJoin>> by lazy {
            LinkedHashMap<String, Pair<OnSplit, OnJoin>>().apply {
                put("kept", OnSplit.SHARED to OnJoin.SUM)
                put("dogShare", OnSplit.FRESH to OnJoin.LOST)
            }
        }
    }
}
