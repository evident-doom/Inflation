package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.Kg
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A world begun in a later era ([inflation.Tuning.startSkills], [inflation.Tuning.startTools]): every people's band knows those
 * ways and tools, at least at the level and share set. It only raises what a band has, so it is the same whether a world is
 * fresh or carried on from a save, and however often it is applied.
 */
internal fun HexGroups.startKnowledge() {
    val skills = startLevels("t.startSkills", tuning.startSkills, Skill.entries)
    val tools = startLevels("t.startTools", tuning.startTools, Tool.entries)
    if (skills.isEmpty() && tools.isEmpty()) return
    var n = 0
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || !g.species.human) continue
        for ((s, level) in skills) { b.knowsSkill[s] = true; b.skills[s] = max(b.skills[s], level); b.lastUsed[s] = b.weeks }
        for ((t, share) in tools) { b.knows[t] = true; b.kit[t] = max(b.kit[t], share) }
        n++
    }
    println("start knowledge for $n bands: ${(skills.map { "${it.first} ${it.second}" } + tools.map { "${it.first} ${it.second}" }).joinToString(", ")}")
}

/** The NAME:level,... list set in [key] ([text]), each name one of [entries] and each level 0-1. */
internal fun <E : Enum<E>> startLevels(key: String, text: String?, entries: List<E>): List<Pair<E, Float>> =
    text?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { item ->
        val (name, level) = item.split(":").map { it.trim() }.let { require(it.size == 2) { "$key: $item is not NAME:level" }; it[0] to it[1] }
        val e = requireNotNull(entries.firstOrNull { it.name == name }) { "$key: no $name (one of ${entries.joinToString(" ")})" }
        val v = requireNotNull(level.toFloatOrNull()) { "$key: $item: the level is no number" }
        require(v in 0f..1f) { "$key: $item: the level is not 0-1" }
        e to v
    } ?: emptyList()

/**
 * A world begun once farming is known ([inflation.Tuning.startSettled]): of people's bands, that share, taken from those on
 * the best farm ground ([farmWorth]), start as villages ([startVillage]); the rest start as foragers, as in any world. Fewer
 * start so where too few bands stand on farm ground. Nothing keeps a village a village afterwards: it stays, moves, sows or
 * splits by the same choices as any band, and the ways of living in houses pass to its neighbours as other ways do.
 * A fresh world starts them as it makes its bands; a world carried on from a save starts them when it is taken up (so a
 * save of a world that had villages, carried on with the key set again, starts more).
 */
internal fun HexGroups.startVillages() {
    val bands = groups.filter { it.alive && it.species.human && it.band != null }
    val farm = bands.filter { farmWorth(it.place) > 0f }.sortedByDescending { farmWorth(it.place) }
    val want = min(farm.size, (tuning.startSettled * bands.size).roundToInt())
    val chosen = if (tuning.startVillagePeople > 0f) gatherVillages(farm, bands, want) else farm.take(want)
    val started = chosen.map { g -> g.band?.let { startVillage(g, it) } }
    val sites = chosen.map { hw.placeHex[it.place] }.distinct().size
    println("villages started: ${started.size} of ${bands.size} bands in $sites hexes, %.0f people a village, with herds ".format(chosen.sumOf { it.size } / sites.coerceAtLeast(1).toDouble()) +
        started.filterNotNull().groupBy { it.first }.entries.joinToString(", ") { (k, l) ->
        "%s %.0f head in %d".format(fauna.kinds[k].name, l.sumOf { it.second.toDouble() }, l.size) }.ifEmpty { "none" })
}

/**
 * Villages begun in being at the era's size ([inflation.Tuning.startVillagePeople]): from the best farm ground down ([farm]),
 * each site gathers the bands nearest it, within [GATHER_RINGS] rings of hexes, onto its own place till it holds that many
 * people, as villages that had grown over generations; at most [want] bands in all. Each band stays a band of its own,
 * co-resident with the rest, and may become one with them as any bands at home together ([becomeOne]). Returns the bands gathered.
 */
private fun HexGroups.gatherVillages(farm: List<HexGroup>, bands: List<HexGroup>, want: Int): List<HexGroup> {
    val byHex = bands.groupBy { hw.placeHex[it.place] }
    val used = HashSet<HexGroup>()
    val out = ArrayList<HexGroup>()
    for (seed in farm) {
        if (out.size >= want) break
        if (seed in used) continue
        val site = seed.place
        var people = 0
        var ring = listOf(hw.placeHex[site]); val seen = hashSetOf(ring[0])
        for (r in 0..GATHER_RINGS) {
            if (r > 0) ring = hex.nextRing(ring, seen)
            for (h in ring) for (g in byHex[h].orEmpty()) {
                if (people >= tuning.startVillagePeople || out.size >= want || g in used) continue
                used += g; out += g; people += g.size
                g.place = site
            }
            if (people >= tuning.startVillagePeople || out.size >= want) break
        }
    }
    return out
}

/** How far, in rings of hexes, a village begun in being draws its bands from (an estimate: a day or two's walk). */
private const val GATHER_RINGS = 3

/**
 * A world begun once herding is known ([inflation.Tuning.startHerders]): of people's mobile bands, that share, taken from those
 * whose camps best feed a herd ([herdForage] of the herd they would start with), start with a herd ([startHerd]); the rest as
 * any band. Like a village, nothing keeps a herding band herding: it keeps, culls, loses or eats its animals by its own choices.
 * Started as [startVillages] is, on a fresh world or on one carried on from a save.
 */
internal fun HexGroups.startHerders() {
    val bands = groups.filter { it.alive && it.species.human && it.band?.atHome == false }
    val worth = bands.associateWith { g -> herdKind(g.place)?.let { k -> herdForage(g.place, fauna.kinds[k], startHerdSize(g, k)).fed } ?: 0f }
    val ground = bands.filter { worth.getValue(it) > 0f }.sortedByDescending { worth.getValue(it) }
    val started = ground.take(min(ground.size, (tuning.startHerders * bands.size).roundToInt())).mapNotNull { g -> g.band?.let { startHerd(g, it) } }
    println("herds started: ${started.size} of ${bands.size} mobile bands, " + started.groupBy { it.first }.entries.joinToString(", ") { (k, l) ->
        "%s %.0f head in %d".format(fauna.kinds[k].name, l.sumOf { it.second.toDouble() }, l.size) }.ifEmpty { "none" })
}

/**
 * The kind a band at [p] would keep: of the plant-eaters living there, the one whose worst of Diamond's criteria
 * ([Species.domestication]) is best, since a kind fails as stock on any one of them (Diamond 1997); null where none live there.
 */
private fun GroupWorld.herdKind(p: Place): Int? = fauna.kinds.indices
    .filter { k -> fauna.kinds[k].let { it.diet.grass + it.diet.browse > 0.5f } && fauna.adults[k][p] * land.areaKm2[p] >= 1f }
    .maxByOrNull { k -> fauna.kinds[k].domestication.min() }

/** The head of kind [k] band [g] starts its herd with: [HERD_START_KG] on the hoof for each of its people. */
private fun GroupWorld.startHerdSize(g: HexGroup, k: Int) = HERD_START_KG * g.size / fauna.kinds[k].massKg

/**
 * Band [g] ([b]) starts as herders where it stands (the kind and head it started with; null, none): a herd of the kind best kept there ([herdKind]), taken from the wild
 * there (no more than live there), grown [HERD_START_TAME] tame, and herding known ([HERD_SKILL]).
 */
private fun GroupWorld.startHerd(g: HexGroup, b: Band): Pair<Int, Float>? {
    val p = g.place
    val k = herdKind(p) ?: return null
    val area = land.areaKm2[p]
    val n = min(startHerdSize(g, k), fauna.adults[k][p] * area)
    if (n <= 0f) return null
    fauna.adults[k][p] -= n / area
    b.herds.herdTame[k] = (b.herds.herdTame[k] * b.herds.herd[k] + HERD_START_TAME * n) / (b.herds.herd[k] + n)
    b.herds.addBred(k, n, 1f)
    b.knowsSkill[Skill.TENDING] = true; b.skills[Skill.TENDING] = max(b.skills[Skill.TENDING], HERD_SKILL); b.lastUsed[Skill.TENDING] = b.weeks
    // (Herders hold herding as their way of life; a village keeps farming.)
    if (b.heldLivelihood == Livelihood.FORAGING) { b.heldLivelihood = Livelihood.HERDING; b.livedOther = 0f }
    return k to n
}

/** How good place [p] is to farm: the share of its grass that is wild cereal, where drinking water is within [VILLAGE_WATER_KM]; else none. */
private fun GroupWorld.farmWorth(p: Place) = if (land.drink.waterKm[p] < VILLAGE_WATER_KM) fauna.food.cereal[p] else 0f

/**
 * Band [g] ([b]) starts as a village where it stands: pit houses for its grown people standing at its camp, kept a year
 * already; quarrels settled by its elders ([DisputeWay.ELDERS]); the ways of living in houses known ([VILLAGE_SKILLS]); sickle and grinding stones; [VILLAGE_STORE_DAYS] of
 * its food in grain; and last autumn's sowing in the ground, enough to ripen to
 * [VILLAGE_FIELD_DAYS] of its food (of ears that hold their grain as [inflation.Tuning.startDomestic] has it; or, where this year's grain has ripened already, that harvest in store).
 */
private fun HexGroups.startVillage(g: HexGroup, b: Band): Pair<Int, Float>? {
    val p = g.place
    val h = hw.placeHex[p]
    for (s in VILLAGE_SKILLS) { b.knowsSkill[s] = true; b.skills[s] = max(b.skills[s], VILLAGE_SKILL); b.lastUsed[s] = b.weeks }
    for (t in listOf(Tool.SICKLE, Tool.GRINDER)) { b.knows[t] = true; b.kit[t] = 1f }
    bands.built += Structure(Built.HOUSES, h, Skill.PIT_HOUSE.ordinal, g.id, g.adults.toFloat()).also { buildOn(it, 1f); it.user = g.id }
    b.houseHex = h; b.house = housesAt(g, h); b.atHome = true
    b.campPlace = p; b.campWeeks = ticksPerYear.roundToInt()
    b.heldLivelihood = Livelihood.FARMING; b.livedOther = 0f
    // (Villages in being: its heads of households settle its quarrels, as in villages that had stood a while (Bandy 2004).)
    if (b.disputeWay < DisputeWay.ELDERS) b.disputeWay = DisputeWay.ELDERS
    b.seedDomestic = tuning.startDomestic
    // Its field ripens with the grass this summer; where the grain has ripened already this year, the harvest is in store.
    val food = fauna.food
    val ripening = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f) < HexFood.SEED_TO
    val harvest = VILLAGE_FIELD_DAYS * g.dailyNeedKg
    if (ripening) { food.sow(p, land.spread(p, Kg(harvest / HexFood.yieldPerKg(1f))), tuning.startDomestic); b.sownPlace = p; b.sownKg = harvest / HexFood.yieldPerKg(1f); b.seedKept = b.sownKg }
    // (Ground and baked, a kilo of grain feeds as a kilo of need: [seedValue] with grinding stones.)
    val stored = VILLAGE_STORE_DAYS * g.dailyNeedKg + if (ripening) 0f else harvest
    addFood(g.stores, g.storeKgPer, Store.SEED, stored * seedValue(b), stored)
    b.seedCorn = g.stores[Store.SEED]; b.seedPulse = VILLAGE_PULSE
    // (Villages kept goats and sheep from the first: the herd a herding band starts with.)
    return startHerd(g, b)
}

/** Ways a village starts knowing: storage bins sealed with clay, pit houses, gruel boiled with hot stones, growing grain; and how well it knows them. */
private val VILLAGE_SKILLS = listOf(Skill.SEALING, Skill.PIT_HOUSE, Skill.STONE_BOILING, Skill.CULTIVATION)
private const val VILLAGE_SKILL = 0.5f
/**
 * Farthest a village stands from drinking water, km; the grain it starts with in store, and the grain its sown field will ripen
 * to, each in days of its own food (estimates: a store that carries it to the harvest, a field that gives half its year's food;
 * no source for either).
 */
private const val VILLAGE_WATER_KM = 3f
private const val VILLAGE_STORE_DAYS = 180f
/**
 * The share of a start village's seed corn that is pulses ([Band.seedPulse]). Lentil, pea and bitter vetch are found with the
 * cereals at the first farming villages (Zohary & Hopf, from memory); their share of the seed sown is not on record: a guess.
 */
private const val VILLAGE_PULSE = 0.25f
private const val VILLAGE_FIELD_DAYS = Date.DAYS_PER_YEAR / 2f
/**
 * A starting herd, kg on the hoof for each of the band's people: a third of the some 4.5 tropical livestock units (1100 kg)
 * a head that people living on their herds alone keep (Dahl and Hjort 1976, from memory, to check), since the first herders
 * still hunted and gathered (the third an estimate); how tame it starts, and how well its band knows herding (estimates).
 */
private const val HERD_START_KG = 370f
private const val HERD_START_TAME = 0.8f
private const val HERD_SKILL = 0.5f
