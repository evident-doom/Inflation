package inflation.ecology

import inflation.world.Hex

/**
 * Who is where, as the step's mechanisms look groups up (docs/design/consolidation-review.md, 4): the living bands by the hex
 * they are in ([bandsIn]), the bands whose herds are kept away from their camps by the hex they are kept in ([herdsKeptIn]),
 * the kinds that follow people by hex ([followersIn]), each living band's hex by its id ([bandHex]), and every group by its
 * id ([group]). All are built together ([build]), at the step's named moments ([Moment]), from the groups as they then are.
 *
 * Built at set moments rather than kept up as groups go: a group dies wherever its last grown one does, on any core, and an
 * index that followed that as it happened would differ with the cores' timing. Between two moments a group may have moved,
 * been made or died: [StaleCheck] counts the reads made after groups moved or were made since the last build, and a reader
 * that must not count the dead skips them ([HexGroup.alive]). Not saved: built afresh each step.
 */
internal class WhoIsWhere {
    /** The moments of the step at which [build] files the groups afresh, in the step's order. */
    enum class Moment {
        /** As the week begins, before any group moves: for the bands' view of the bands about ([fillBandStates]). */
        WEEK_BEGINS,
        /** After the week's moves and meetings, as the hunting begins: for the ways the bands set, the beasts at their herds ([herdOdds]) and the feasts on kills ([feastOnKills]). */
        MOVED,
        /** As the births and deaths begin and the bands have their own weeks ([demography]). */
        DEMOGRAPHY,
        /** After the births, splits and the month's camp moves, as small bands join kin ([mergeAndPair]). */
        MERGING,
    }

    /** The moment it was last built at; null before the first. */
    var builtAt: Moment? = null; private set

    private var bands: Map<Hex, List<HexGroup>> = emptyMap()
    private var herds: Map<Hex, List<HexGroup>> = emptyMap()
    private var followers: Map<Hex, List<HexGroup>> = emptyMap()
    private var bandHexes: Map<Int, Hex> = emptyMap()
    private var byId: Map<Int, HexGroup> = emptyMap()
    /** [StaleCheck]'s stamps of the last build: by groups moved or made ([StaleCheck.stamp]), and by groups made ([StaleCheck.madeStamp]). */
    private var movedStamp = 0L
    private var madeStamp = 0L

    /** Files every group of [gs] afresh, at moment [at]. */
    fun build(gs: HexGroups, at: Moment) {
        val hw = gs.hw
        val bands = HashMap<Hex, ArrayList<HexGroup>>(); val herds = HashMap<Hex, ArrayList<HexGroup>>()
        val followers = HashMap<Hex, ArrayList<HexGroup>>(); val bandHexes = HashMap<Int, Hex>()
        val byId = HashMap<Int, HexGroup>(gs.groups.size * 2)
        for (g in gs.groups) {
            byId[g.id] = g
            if (!g.alive) continue
            val h = hw.placeHex[g.place]
            val b = g.band
            if (b != null) {
                bands.getOrPut(h) { ArrayList() } += g
                bandHexes[g.id] = h
                // (A herd kept away from its band's camp: [Herd.at], for the beasts that hunt there.)
                val kept = b.herds.at
                if (!kept.none && hw.placeHex[kept] != h) herds.getOrPut(hw.placeHex[kept]) { ArrayList() } += g
            } else if (g.species.followsPeople && !g.species.human) followers.getOrPut(h) { ArrayList() } += g
        }
        this.bands = bands; this.herds = herds; this.followers = followers; this.bandHexes = bandHexes; this.byId = byId
        movedStamp = StaleCheck.stamp(); madeStamp = StaleCheck.madeStamp(); builtAt = at
    }

    /** The living bands in hex [h], as last built, in the groups' order. */
    fun bandsIn(h: Hex): List<HexGroup> { StaleCheck.readThrough("bands in a hex", movedStamp, byId = false); return bands[h].orEmpty() }

    /** The living bands whose herds are kept in hex [h], away from their camps ([Herd.at]), as last built. */
    fun herdsKeptIn(h: Hex): List<HexGroup> { StaleCheck.readThrough("herds kept in a hex", movedStamp, byId = false); return herds[h].orEmpty() }

    /** The living groups of kinds that follow people (not people) in hex [h], as last built: for taming ([keepAnimals]). */
    fun followersIn(h: Hex): List<HexGroup> { StaleCheck.readThrough("followers in a hex", movedStamp, byId = false); return followers[h].orEmpty() }

    /** The hex living band [id] was in, as last built; null for a band that was not living then, or a group not a band. */
    fun bandHex(id: Int): Hex? { StaleCheck.readThrough("a band's hex", movedStamp, byId = false); return bandHexes[id] }

    /** Group [id], living or not, as of the last build (where it is now: its own [HexGroup.place]); null for one made since. */
    fun group(id: Int): HexGroup? { StaleCheck.readThrough("a group by id", madeStamp, byId = true); return byId[id] }
}
