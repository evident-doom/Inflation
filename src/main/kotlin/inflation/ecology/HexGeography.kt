package inflation.ecology

import inflation.world.Biome
import inflation.world.HexWorld
import inflation.world.RiverClass
import kotlin.random.Random

/**
 * The great features of the country, each with a handle ([Feature.id]), a name and the hexes it covers: the
 * rivers and their valleys and floodplains, lakes, mountains, the open country, woods and wetlands, bays,
 * peninsulas and islands. They are what a map of the country for people would name, the frame in which places
 * ([HexRoutes]) are told: the ford on such a river, in such a steppe.
 *
 * A stretch of country counts as a feature of its own when it is larger than the ground one place draws on
 * ([GATHER_KM] across); smaller, it is a patch within someone's home country. Rivers are the main stems of the
 * rivers ([RiverClass.RIVER] and greater), the greater flow keeping the name where two meet; a valley is the
 * country draining to a river before any other, a floodplain the river's own flats. Bays and peninsulas are
 * judged at the same scale: sea mostly closed in by land within half that distance, land mostly surrounded by
 * sea. Built once from the land as the world made it.
 */
class HexGeography(private val hw: HexWorld) {
    enum class Kind(val label: String) {
        RIVER("river"), VALLEY("valley"), FLOODPLAIN("floodplain"), LAKE("lake"), MOUNTAINS("mountains"), HILLS("hills"),
        FOREST("forest"), DEEP_FOREST("deep forest"), WOODLAND("woods"), STEPPE("steppe"), DESERT("desert"),
        MARSH("marsh"), BAY("bay"), STRAIT("strait"), PENINSULA("peninsula"), ISTHMUS("isthmus"), ISLAND("island"),
    }

    /** One feature: [hexes] in order from source to mouth for a river; [parent], the feature it lies in or joins (-1: none). */
    class Feature(val id: Int, val kind: Kind, val hexes: IntArray, var parent: Int, var name: String = "") {
        /** The hex to write its name at: the middle of a river, the heart (farthest from its edge) of a stretch of country. */
        var label = hexes[hexes.size / 2]
    }

    private val hex = hw.hex
    private val world = hw.world
    val all = ArrayList<Feature>()
    /** Per hex: the features it lies in, smallest first. */
    val at: Array<IntArray>

    private fun land(h: Int) = hw.land[h] > 0.5f
    private val rings = (GATHER_KM / 2 / hex.hexKm).toInt()
    private val minHexes = (Math.PI * (GATHER_KM / 2) * (GATHER_KM / 2) / hex.areaKm2).toInt()
    /** Valleys and floodplains to the river they belong to (and are named after). */
    private val riverOf = HashMap<Int, Int>()

    init {
        rivers()
        for ((kind, hexes) in regions()) add(kind, hexes)
        coast()
        // What lies in what: a stretch of country in the smallest greater one that holds most of it.
        val inside = Array(hex.size) { ArrayList<Int>() }
        for (f in all) if (f.kind != Kind.RIVER) for (h in f.hexes) inside[h] += f.id
        for (f in all) {
            if (f.parent >= 0 || f.kind == Kind.RIVER) continue
            val count = HashMap<Int, Int>()
            for (h in f.hexes) for (o in inside[h]) if (o != f.id && all[o].hexes.size > f.hexes.size) count.merge(o, 1, Int::plus)
            f.parent = count.filter { it.value * 2 > f.hexes.size }.keys.minByOrNull { all[it].hexes.size } ?: -1
        }
        for (f in all) if (f.kind == Kind.RIVER) for (h in f.hexes) if (f.id !in inside[h]) inside[h] += f.id
        at = Array(hex.size) { h -> inside[h].sortedBy { all[it].hexes.size }.toIntArray() }
        for (f in all) if (f.kind != Kind.RIVER) f.label = heart(f.hexes)
        name()
    }

    @IgnorableReturnValue private fun add(kind: Kind, hexes: IntArray, parent: Int = -1): Feature {
        val f = Feature(all.size, kind, hexes, parent); all += f; return f
    }

    /** The rivers, by their main stems; then each one's valley and floodplain. */
    private fun rivers() {
        val grid = world.grid; val water = world.water; val recv = water.drainage.receiver; val q = water.discharge
        val named = BooleanArray(grid.size) { world.riverClass(it) >= RiverClass.RIVER }
        // Who flows into each river cell (only river cells).
        val start = IntArray(grid.size + 1)
        for (i in 0 until grid.size) if (named[i] && recv[i] != i && named[recv[i]]) start[recv[i] + 1]++
        for (i in 0 until grid.size) start[i + 1] += start[i]
        val donors = IntArray(start[grid.size]); val fill = start.copyOf()
        for (i in 0 until grid.size) if (named[i] && recv[i] != i && named[recv[i]]) donors[fill[recv[i]]++] = i
        // From each river's end up its greater branch to the source; each lesser branch is a river of its own, joining it.
        val stemOf = IntArray(grid.size) { -1 }
        val stems = ArrayList<IntArray>(); val joins = ArrayList<Int>()
        val todo = ArrayDeque<Pair<Int, Int>>()
        for (i in 0 until grid.size) if (named[i] && (recv[i] == i || !named[recv[i]])) todo += i to -1
        while (todo.isNotEmpty()) {
            val (end, into) = todo.removeFirst()
            val id = stems.size; val cells = ArrayList<Int>(); var cur = end
            while (true) {
                cells += cur; stemOf[cur] = id
                var best = -1
                for (k in start[cur] until start[cur + 1]) { val d = donors[k]; if (best < 0 || q[d] > q[best]) best = d }
                if (best < 0) break
                for (k in start[cur] until start[cur + 1]) { val d = donors[k]; if (d != best) todo += d to id }
                cur = best
            }
            cells.reverse(); stems += cells.toIntArray(); joins += into
        }
        val riverId = IntArray(stems.size)
        for ((s, cells) in stems.withIndex()) {
            val hexes = ArrayList<Int>()
            for (c in cells) { val h = hex.owner[c]; if (hexes.lastOrNull() != h) hexes += h }
            riverId[s] = add(Kind.RIVER, hexes.toIntArray()).id
        }
        for (s in stems.indices) if (joins[s] >= 0) all[riverId[s]].parent = riverId[joins[s]]
        // The valley: the cells draining to the river before any other (followed down, with the way remembered).
        val valleyOf = IntArray(grid.size) { -2 }
        for (i in 0 until grid.size) if (stemOf[i] >= 0) valleyOf[i] = stemOf[i]
        val path = ArrayList<Int>()
        for (i0 in 0 until grid.size) {
            if (valleyOf[i0] != -2 || world.isSea[i0]) continue
            path.clear(); var i = i0
            while (valleyOf[i] == -2 && !world.isSea[i] && recv[i] != i) { path += i; i = recv[i] }
            val v = if (valleyOf[i] == -2) -1 else valleyOf[i]
            for (p in path) valleyOf[p] = v
            if (valleyOf[i] == -2) valleyOf[i] = v
        }
        // Each land hex to the valley and floodplain holding most of its land.
        val vCount = Array(hex.size) { HashMap<Int, Int>() }; val fCount = Array(hex.size) { HashMap<Int, Int>() }; val landN = IntArray(hex.size)
        val fp = world.floodplain
        for (i in 0 until grid.size) {
            if (world.isSea[i] || water.isLake(i)) continue
            val h = hex.owner[i]; landN[h]++
            if (valleyOf[i] >= 0) vCount[h].merge(valleyOf[i], 1, Int::plus)
            val r = fp.riverOf[i]
            if (fp.isFloodplain[i] && r >= 0 && stemOf[r] >= 0) fCount[h].merge(stemOf[r], 1, Int::plus)
        }
        val valleys = Array(stems.size) { ArrayList<Int>() }; val plains = Array(stems.size) { ArrayList<Int>() }
        for (h in 0 until hex.size) {
            if (!land(h)) continue
            vCount[h].maxByOrNull { it.value }?.let { valleys[it.key] += h }
            fCount[h].maxByOrNull { it.value }?.let { if (it.value * 2 > landN[h]) plains[it.key] += h }
        }
        val valleyId = IntArray(stems.size) { -1 }
        for (s in stems.indices) if (valleys[s].isNotEmpty()) valleyId[s] = add(Kind.VALLEY, valleys[s].toIntArray()).id
        for (s in stems.indices) if (joins[s] >= 0 && valleyId[s] >= 0) all[valleyId[s]].parent = valleyId[joins[s]]
        for (s in stems.indices) if (valleyId[s] >= 0) riverOf[valleyId[s]] = riverId[s]
        for (s in stems.indices) if (plains[s].isNotEmpty()) riverOf[add(Kind.FLOODPLAIN, plains[s].toIntArray(), valleyId[s]).id] = riverId[s]
    }

    /** The country by what covers it: mountains, then the kind of ground most of the hex is; its stretches large enough. */
    private fun regions(): List<Pair<Kind, IntArray>> {
        val cover = arrayOfNulls<Kind>(hex.size)
        for (h in 0 until hex.size) {
            if (!land(h)) continue
            mountain(h)?.let { cover[h] = it; continue }
            val b = Biome.entries.maxBy { hw.biome[it.ordinal][h] }
            cover[h] = when (b) {
                Biome.CONIFER_FOREST, Biome.TEMPERATE_FOREST, Biome.SWAMP_FOREST, Biome.MANGROVE, Biome.FLOODPLAIN_FOREST -> Kind.FOREST
                Biome.OPEN_WOODLAND, Biome.MEDITERRANEAN_WOODLAND -> Kind.WOODLAND
                Biome.STEPPE, Biome.SHRUBLAND, Biome.ALPINE_MEADOW -> Kind.STEPPE
                Biome.SEMI_DESERT, Biome.DESERT, Biome.SALT_FLAT -> Kind.DESERT
                Biome.MARSH, Biome.BOG, Biome.SALT_MARSH -> Kind.MARSH
                else -> null
            }
        }
        val out = ArrayList<Pair<Kind, IntArray>>()
        for (c in components { cover[it] != null }.groupBySame { cover[it] }) if (c.size >= minHexes) {
            out += cover[c[0]]!! to c
            // The deep forest: where it is more than half a place's ground from the forest's edge.
            if (cover[c[0]] == Kind.FOREST) {
                val inC = HashSet(c.toList())
                val deep = c.filter { h -> ringsTo(h) { it !in inC } > rings }.toSet()
                for (d in components { it in deep }) out += Kind.DEEP_FOREST to d
            }
        }
        for (c in components { hw.lake[it] > 0.5f }) out += Kind.LAKE to c
        return out
    }

    /**
     * Mountain country as geographers map it (Kapos et al. 2000, for UNEP-WCMC): high (over 2500 m), or over 300 m
     * with more than 300 m between the lowest and highest ground about it (the hex, about 7 km either way of its
     * centre). Its lowest class, under 1000 m, is told apart as hills.
     */
    private fun mountain(h: Int): Kind? {
        val mid = hex.midM[h]; val relief = hex.highM[h] - hex.lowM[h]
        return when {
            mid >= 2500f || (mid >= 1000f && relief > 300f) -> Kind.MOUNTAINS
            mid >= 300f && relief > 300f -> Kind.HILLS
            else -> null
        }
    }

    /**
     * Bays, peninsulas and islands. A peninsula is land narrower than the ground a place draws on ([GATHER_KM]) that
     * runs out from broader land, a bay the same of the sea: what is left when the land (the sea) is pared back by half
     * that width and grown out again by as much (a morphological opening), the narrow parts not growing back.
     */
    private fun coast() {
        fun sea(h: Int) = hw.sea[h] > 0.5f
        val lands = components { land(it) }.sortedByDescending { it.size }
        // (Land running off the map may be part of more beyond it: not taken for an island.)
        val islands = lands.drop(1).filter { c -> c.all { h -> (0 until 6).all { d -> hex.neighbour(h, d) >= 0 } } }
        val island = BooleanArray(hex.size); for (c in islands) for (h in c) island[h] = true
        // The separate land masses (lakes counted with the land), to tell a strait between two of them.
        val massOf = IntArray(hex.size) { -1 }
        for ((i, c) in components { !sea(it) }.withIndex()) for (h in c) massOf[h] = i
        // Of the narrow parts, those at least as long as the width they are judged at; one running between two
        // broad stretches, or water between two land masses each with a long shore on it, is a neck (an isthmus, a strait), not a peninsula or
        // bay. Land is judged at two widths, the ground one place draws on and a range ([RANGE_KM]); a part
        // found the same at both is one feature.
        val seen = HashSet<List<Int>>()
        for (widthKm in doubleArrayOf(GATHER_KM, RANGE_KM)) {
            val k = Math.ceil(widthKm / 2 / hex.hexKm).toInt()
            fun narrowParts(inside: (Int) -> Boolean, end: Kind, neck: Kind) {
                val core = opened(k, inside)
                val coreId = IntArray(hex.size) { -1 }
                for ((i, c) in components { core[it] }.withIndex()) for (h in c) coreId[h] = i
                for (c in components { inside(it) && !core[it] && !island[it] }) {
                    if (c.size < 2 * k + 1 || !seen.add(c.sorted())) continue
                    val round = c.flatMap { h -> (0 until 6).map { hex.neighbour(h, it) } }.filter { it >= 0 }
                    val cores = round.filter { coreId[it] >= 0 }.map { coreId[it] }.toSet()
                    // (Water between two land masses: most of it lies within the judged half width of both, with the two
                    // on opposite sides of it.)
                    val between = end == Kind.BAY && c.count { h -> betweenMasses(h, k, massOf) } * 2 > c.size
                    add(if (cores.size >= 2 || between) neck else end, c)
                }
            }
            narrowParts({ !sea(it) }, Kind.PENINSULA, Kind.ISTHMUS)
            // (At a range's width the sea's narrow parts run together round the rim of a broad bay; seas only at the narrower.)
            if (widthKm == GATHER_KM) narrowParts({ sea(it) }, Kind.BAY, Kind.STRAIT)
        }
        for (c in islands) add(Kind.ISLAND, c)
    }

    /** Whether two land masses lie within [k] rings of sea hex [h] on opposite sides of it (more than a right angle apart). */
    private fun betweenMasses(h: Int, k: Int, massOf: IntArray): Boolean {
        val nearest = HashMap<Int, Int>()
        val seen = hashSetOf(h); var ring = listOf(h)
        for (r in 0 until k) { ring = hex.nextRing(ring, seen); for (q in ring) if (massOf[q] >= 0) nearest.putIfAbsent(massOf[q], q) }
        if (nearest.size < 2) return false
        val (x, y) = hex.centreKm(h)
        val dirs = nearest.values.map { q -> hex.centreKm(q).let { (qx, qy) -> qx - x to qy - y } }
        return dirs.indices.any { a -> (a + 1 until dirs.size).any { b -> dirs[a].first * dirs[b].first + dirs[a].second * dirs[b].second < 0 } }
    }

    /** The hexes for which [inside] holds that an opening by [k] rings keeps: all but the parts narrower than 2[k] + 1 hexes. */
    private fun opened(k: Int, inside: (Int) -> Boolean): BooleanArray {
        // (Beyond the map's edge counts as more of the same, so the edge makes nothing narrow.)
        var core = BooleanArray(hex.size) { inside(it) }
        repeat(k) {
            val c = core
            core = BooleanArray(hex.size) { h -> c[h] && (0 until 6).all { d -> hex.neighbour(h, d).let { q -> q < 0 || c[q] } } }
        }
        repeat(k) {
            val c = core
            core = BooleanArray(hex.size) { h -> c[h] || inside(h) && (0 until 6).any { d -> hex.neighbour(h, d).let { q -> q >= 0 && c[q] } } }
        }
        return core
    }

    /** The connected stretches of hexes for which [inside] holds. */
    private fun components(inside: (Int) -> Boolean): List<IntArray> {
        val seen = BooleanArray(hex.size); val out = ArrayList<IntArray>()
        for (h0 in 0 until hex.size) {
            if (seen[h0] || !inside(h0)) continue
            val c = ArrayList<Int>(); val stack = arrayListOf(h0); seen[h0] = true
            while (stack.isNotEmpty()) {
                val h = stack.removeAt(stack.size - 1); c += h
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && !seen[q] && inside(q)) { seen[q] = true; stack += q } }
            }
            out += c.toIntArray()
        }
        return out
    }

    /** Split each stretch into the connected parts that share a [key]. */
    private fun List<IntArray>.groupBySame(key: (Int) -> Any?): List<IntArray> = flatMap { c ->
        val set = c.toHashSet()
        val seen = HashSet<Int>(); val out = ArrayList<IntArray>()
        for (h0 in c) {
            if (!seen.add(h0)) continue
            val k = key(h0); val part = ArrayList<Int>(); val stack = arrayListOf(h0)
            while (stack.isNotEmpty()) {
                val h = stack.removeAt(stack.size - 1); part += h
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && q in set && q !in seen && key(q) == k) { seen += q; stack += q } }
            }
            out += part.toIntArray()
        }
        out
    }

    /** Rings from [h] out to the nearest hex for which [outside] holds, or to the map's edge; past [rings], [rings] + 2. */
    private fun ringsTo(h: Int, outside: (Int) -> Boolean): Int {
        val seen = hashSetOf(h); var ring = listOf(h)
        for (r in 1..rings + 1) {
            if (ring.any { q -> (0 until 6).any { hex.neighbour(q, it) < 0 } }) return r
            ring = hex.nextRing(ring, seen)
            if (ring.any(outside)) return r
        }
        return rings + 2
    }

    /** The hex of [hexes] farthest from their edge. */
    private fun heart(hexes: IntArray): Int {
        val inF = hexes.toHashSet()
        val depth = HashMap<Int, Int>(); var front = hexes.filter { h -> (0 until 6).any { d -> hex.neighbour(h, d).let { it < 0 || it !in inF } } }
        for (h in front) depth[h] = 0
        var k = 0
        while (front.isNotEmpty()) {
            k++
            val next = ArrayList<Int>()
            for (h in front) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && q in inF && q !in depth) { depth[q] = k; next += q } }
            front = next
        }
        return depth.maxByOrNull { it.value }?.key ?: hexes[0]
    }

    /** A name for each: a made-up word for each river and stretch of country; valleys and floodplains after their river. */
    private fun name() {
        val used = HashSet<String>()
        // Each name drawn from where the feature is (a river by its mouth, the rest by their heart), the greatest
        // first, so a name stays with its feature when others are found or lost.
        for (f in all.sortedByDescending { it.hexes.size }) {
            if (f.kind == Kind.VALLEY || f.kind == Kind.FLOODPLAIN) continue
            val at = if (f.kind == Kind.RIVER) f.hexes.last() else f.label
            val rnd = Random(world.config.seed * 1_000_003 + at.toLong() * 31 + f.kind.ordinal)
            var w: String
            do { w = word(rnd) } while (!used.add(w))
            f.name = when (f.kind) {
                Kind.RIVER -> "$w River"; Kind.LAKE -> "Lake $w"; Kind.BAY -> "$w Bay"; Kind.STRAIT -> "$w Strait"
                Kind.DEEP_FOREST -> "the Deep $w"
                else -> "$w ${f.kind.label.replaceFirstChar { it.uppercase() }}"
            }
        }
        for (f in all) if (f.kind == Kind.VALLEY || f.kind == Kind.FLOODPLAIN) {
            val river = all[riverOf[f.id] ?: continue].name.removeSuffix(" River")
            f.name = "$river ${f.kind.label.replaceFirstChar { it.uppercase() }}"
        }
    }

    private fun word(rnd: Random): String {
        val on = arrayOf("", "b", "d", "g", "h", "k", "l", "m", "n", "r", "s", "t", "v", "sh", "kh", "th", "z")
        val nu = arrayOf("a", "e", "i", "o", "u", "a", "e", "ai", "ou")
        val coda = arrayOf("", "", "", "n", "r", "l", "s", "m", "k")
        val n = 2 + rnd.nextInt(2)
        val s = (0 until n).joinToString("") { on.random(rnd) + nu.random(rnd) } + coda.random(rnd)
        return s.replaceFirstChar { it.uppercase() }
    }

    /** For the check: features by kind, how many and the largest. */
    fun summary(): String = Kind.entries.mapNotNull { k ->
        val of = all.filter { it.kind == k }
        if (of.isEmpty()) null else {
            val big = of.maxBy { it.hexes.size }
            "${k.label} ${of.size} (largest ${big.name}, ${if (k == Kind.RIVER) "${(big.hexes.size * hex.hexKm).toInt()} km" else "${(big.hexes.size * hex.areaKm2).toInt()} km²"})"
        }
    }.joinToString(", ")

    /** How [h] is told: the features it lies in, smallest first, e.g. "Tamu Floodplain, in Kela Steppe". */
    fun where(h: Int): String = at[h].joinToString(", ") { all[it].name }
}
