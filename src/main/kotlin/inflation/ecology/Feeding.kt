package inflation.ecology

import inflation.world.get
import inflation.world.set
import kotlin.math.max
import kotlin.math.min

// How an animal feeds where it is: where its food comes from, the one rule by which every kind eats its diet, and the protein in what it ate.

/** Where an animal's food at a place comes from, kg per m² asked for and got: eaten from its stocks ([HexFauna.graze]), or only reckoned on ([DryPantry]). */
internal interface Pantry {
    fun grass(kg: Float): Float
    fun browse(kg: Float): Float
    fun fruit(kg: Float): Float
    fun mast(kg: Float): Float
    fun roots(kg: Float): Float
}

/**
 * A place's food as it is only reckoned on (the residents' [HexFauna.roomFor]): what an animal would get of each, taken
 * off what is there, without eating it.
 */
internal class DryPantry : Pantry {
    var reach = 0f; var grass = 0f; var browse = 0f; var fruit = 0f; var mast = 0f; var roots = 0f
    override fun grass(kg: Float): Float { val t = min(kg, reach * grass); grass -= t; return t }
    override fun browse(kg: Float): Float { val t = min(kg, reach * browse); browse -= t; return t }
    override fun fruit(kg: Float): Float { val t = min(kg, reach * fruit); fruit -= t; return t }
    override fun mast(kg: Float): Float { val t = min(kg, reach * mast); mast -= t; return t }
    override fun roots(kg: Float): Float { val t = min(kg, reach * roots); roots -= t; return t }
}

/**
 * What an animal of a kind eats at a place in a week, kg per m² of it, and what it [got] of nourishment (see [forage]):
 * filled in by [forage], so one can be kept and filled again (the residents graze every place, every kind, every week).
 */
internal class Bites {
    var grass = 0f; var browse = 0f; var fruit = 0f; var mast = 0f; var roots = 0f
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var mast2 = 0f; var roots2 = 0f
    var short = 0f; var room = 0f; var q = 0f; var got = 0f
    /** All it ate, kg per m². */
    val eaten get() = grass + grass2 + browse + browse2 + fruit + fruit2 + mast + mast2 + roots + roots2
}

/**
 * The share of the grass it would eat that kind [s] gets at under [snowMm] of snow (mm water): all of it on bare ground,
 * falling evenly to none at the depth it can dig through no more ([Species.snowLimitMm]). Grazing under snow is
 * digging: the deeper the snow, the more snow it moves and the more of its feeding time goes to clearing each mouthful,
 * until at its limit digging takes all of it. (The even fall is the simplest shape between the two ends the record
 * gives, bare ground and the limit. Digging is cheap work, caribou spend 118-481 J a stroke, Fancy and White 1985, so
 * the snow is felt as time lost from feeding, not as fat.)
 */
internal fun grassUnderSnow(s: Species, snowMm: Float): Float = (1f - snowMm / s.snowLimitMm).coerceIn(0f, 1f)

/**
 * Rooting: the ground an animal of [ROOTING_MASS_KG] searches for roots and bulbs in a day, m² ([inflation.Tuning.rootingM2PerDay]), and the days it takes to dig
 * out a kg of them (in [Nourishment.ROOTS]' kg). An estimate: in a Hungarian oak forest wild boar turned over 8-11% of the
 * floor in a year (Calosi et al. 2025 and the rooting studies they cite), some 50 m² dug a boar a day at a few boar a km², and
 * they nose over more than they dig; rooting took about three quarters of their foraging time in autumn and winter (a fenced
 * Highland study, as cited there). The ground searched goes with the body as mass^0.75. To tune.
 */
internal const val ROOTING_DAYS_PER_KG = 0.2f
internal const val ROOTING_MASS_KG = 90f

/**
 * The most roots animals of [s] at [perKm2] a km² dig in [days], kg per m² of their place, where [rootsKgM2] can be dug
 * there: each finds them as it searches its ground ([searchM2], m² a day, [inflation.Tuning.rootingM2PerDay]) and spends time digging each kg out
 * ([ROOTING_DAYS_PER_KG]), a Holling disc: a R / (1 + a h R) a day. So roots thin on the ground are found slowly, and a
 * place dug over feeds less and less, however much else fell short (docs: Olli 2026-11-23, rooting by search, not a bound on
 * switching).
 */
internal fun rootingMost(s: Species, perKm2: Float, rootsKgM2: Float, days: Int, searchM2: Float): Float {
    if (!(perKm2 > 0f) || !(rootsKgM2 > 0f)) return 0f
    val a = searchM2 * kotlin.math.sqrt(kotlin.math.sqrt((s.massKg / ROOTING_MASS_KG).let { it * it * it }))
    return perKm2 * 1e-6f * days * a * rootsKgM2 / (1f + a * ROOTING_DAYS_PER_KG * rootsKgM2)
}

/**
 * How animals of [s] eating diet [d] feed where the forage nourishes [placeQ] (as it picks it over, [selected]), asking [need] kg per m² this week, from
 * [pantry]: grass, mast and roots (as far as it gets at them under the snow, [underSnow], see [grassUnderSnow]), browse as far as the gut fills at that nourishment, fruit, then
 * switching between them for a shortfall; into [out]. The standing straw is the cured grass, eaten as grass at what it
 * nourishes ([HexPlants.nourishment]). Each food feeds by its energy ([Nourishment]): grass and browse at [q], fruit, mast and roots
 * at theirs, all by [Species.needScale] in kg of its need ([Bites.got], [Bites.short]). The one rule for the residents' week's grazing
 * ([HexFauna.graze]) and for the room they reckon a place has for more of them ([HexFauna.roomFor]). [cropG] and [cropB] are
 * the shares of its want of grass and of browse it can crop where they stand ([cropRate]); [digMost] the roots it can find
 * and dig out in the week, kg per m² ([rootingMost]).
 */
@IgnorableReturnValue internal fun forage(s: Species, d: Diet, placeQ: Float, need: Float, underSnow: Float, pantry: Pantry, out: Bites,
    cropG: Float = 1f, cropB: Float = 1f, digMost: Float = Float.POSITIVE_INFINITY): Bites {
    // (The forage as it picks it over, [selected]: a small kind eats better than the place's mean.)
    val q = s.selected(placeQ)
    // (Worked in energy, [Nourishment]'s units: a kg of green forage feeds a unit.)
    val want = need / s.needScale
    val total = d.plants.coerceAtLeast(1e-6f)
    // Horses and asses eat as much of poor forage as of good; ruminants eat less of it ([gutFill]).
    val fill = gutFill(s, q)
    // (As the residents: what it cannot get at is a share of the place's food it cannot reach, not a share
    // of its need it goes without; where food is plenty it still eats its fill.)
    // (And as far as it can crop the grass and browse where they stand as thick as they do: [cropG] and [cropB], [cropRate].)
    val grass = pantry.grass(cropG * underSnow * want * fill * d.grass / total)
    // (Browse at most (1 + SWITCH) times its share of the kind's own diet, however it leans for protein or makes up a
    // shortfall: a grazer on poor grass loses condition rather than turning browser. Without the bound the aurochs, a
    // grazer of 0.2 browse, met 0.53-0.63 of its need with shoots and ate 41-47% of all the residents' browse, and roe,
    // elk and deer starved (eval 2026-11-19, the plant model with real shrub and herb weights).)
    val sd = s.diet
    val browseMost = (1f + SWITCH) * want * fill * sd.browse / sd.plants.coerceAtLeast(1e-6f)
    val browse = pantry.browse(min(cropB * want * fill * d.browse / total, browseMost))
    val fruit = pantry.fruit(want * d.fruit / total / Nourishment.FRUIT)
    // (Roots and the mast on the ground lie under the snow as the grass does, and are got at the same way: boar root less
    // the deeper the snow, keep to oak woods under 40 cm of it and still find acorns there, Chiyojima et al., Mammalian
    // Science 2022; deep snow stops their rooting and starves them, Okarma et al., Acta Theriologica 1995.)
    val mast = pantry.mast(underSnow * want * d.mast / total / Nourishment.MAST)
    // (Roots as far as it finds and digs them out in the week, [digMost], [rootingMost].)
    val roots = pantry.roots(min(digMost, underSnow * want * d.roots / total / Nourishment.ROOTS))
    // Diet switching: a food that fell short is made up from the others: grass and browse as far as the gut holds,
    // fruit, mast and roots as far as the place has them (a hungry animal eats what of its foods it finds; an ostrich out of
    // fruit season picks more shoots and leaves).
    var gap = want - ((grass + browse) * q + fruit * Nourishment.FRUIT + mast * Nourishment.MAST + roots * Nourishment.ROOTS)
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var mast2 = 0f; var roots2 = 0f
    // But the gut holds only so much bulk a day: on poor forage a ruminant cannot make up by eating
    // more of it (switching shifts what it eats, not how much its gut takes).
    // (Its need is reckoned in forage of middling quality, [HexFauna.GUT_QREF]: on that it fills its need exactly.)
    var room = max(0f, want * fill / HexFauna.GUT_QREF - (grass + browse))
    // (A kind that digests fibre poorly, a pig or a bear, makes up a shortfall with grass and browse only to (1 + SWITCH)
    // times their share of its diet: it cannot fill up on forage when its mast, roots and fruit fail, and goes hungry
    // with green forage about it, as wild boar do after a poor mast or under snow. Made up as far as the gut held, a
    // boar's quarter of forage rose to two fifths of its food and it never wanted.)
    if (s.fibreDigestion < 1f) room = min(room, max(0f, (1f + SWITCH) * want * fill * (d.grass + d.browse) / total - (grass + browse)))
    // (What it switches to nourishes it as well as its gut digests fibre, [Species.fibreDigestion].)
    val qf = q * s.fibreDigestion
    // (But grass the snow buries is made up with browse to at most (1 + SWITCH) times its usual share. A
    // grazer does not turn browser when the snow buries its grass: Przewalski's horses graze all winter in the Gobi, and
    // the khulan's winter food that looked like shrubs in its hair was mostly grass too: Burnik Sturm et al., J Appl Ecol
    // 2017, and the DNA barcoding after. Grass that fell short otherwise, eaten out or poor, is still made up as far as
    // the gut holds: capping that too starved grazers and wild sheep wherever the lean season comes without snow.)
    val buried = (1f - underSnow) * want * fill * d.grass / total * q
    // (Made up first from the forage it mostly lives on, as it leans for protein ([HexFauna.leanDiet]), then from the other,
    // as a hunter makes up its meat: made up browse first, the grazers short of energy on cured grass ate the shoots away
    // from the browsers while the grass stood uneaten, eval 2026-11-19 diet-by-kind: aurochs took 41-47% of all the
    // residents' browse, horses and gazelle met a third to two thirds of their need with it, roe and deer starved.)
    fun makeBrowse() { if (gap > 0f && d.browse > 0f) {
        browse2 = pantry.browse(min(max(0f, browseMost - browse), cropB * min(min(gap / qf, room), max(0f, gap - buried) / qf + SWITCH * want * fill * d.browse / total)))
        gap -= browse2 * qf; room -= browse2 } }
    fun makeGrass() { if (gap > 0f && d.grass > 0f && underSnow > 0f) { grass2 = pantry.grass(cropG * underSnow * min(gap / qf, room)); gap -= grass2 * qf; room -= grass2 } }
    if (d.grass >= d.browse) { makeGrass(); makeBrowse() } else { makeBrowse(); makeGrass() }
    if (gap > 0f && d.fruit > 0f) { fruit2 = pantry.fruit(gap / Nourishment.FRUIT); gap -= fruit2 * Nourishment.FRUIT }
    if (gap > 0f && d.mast > 0f && underSnow > 0f) { mast2 = pantry.mast(underSnow * gap / Nourishment.MAST); gap -= mast2 * Nourishment.MAST }
    if (gap > 0f && d.roots > 0f && underSnow > 0f) { roots2 = pantry.roots(min(max(0f, digMost - roots), underSnow * gap / Nourishment.ROOTS)); gap -= roots2 * Nourishment.ROOTS }
    val short = max(0f, gap) * s.needScale
    // Grass and browse nourish fully only when green; fruit, mast and roots keep their value. (What it switched to, as its gut
    // digests fibre, [qf], as the gap was reckoned: counted at [q], a boar's or a bear's switched forage fed it twice what
    // it was reckoned to, and a camel's two thirds.)
    val got = ((grass + browse) * q + (grass2 + browse2) * qf + (fruit + fruit2) * Nourishment.FRUIT + (mast + mast2) * Nourishment.MAST +
        (roots + roots2) * Nourishment.ROOTS) * s.needScale
    out.grass = grass; out.browse = browse; out.fruit = fruit; out.mast = mast; out.roots = roots
    out.grass2 = grass2; out.browse2 = browse2; out.fruit2 = fruit2; out.mast2 = mast2; out.roots2 = roots2
    out.short = short; out.room = room; out.q = q; out.got = got
    return out
}

/** The protein in [b], kg per m², with grass and browse of the protein shares [grass] and [browse]. */
internal fun protein(b: Bites, grass: Float, browse: Float): Float =
    (b.grass + b.grass2) * grass + (b.browse + b.browse2) * browse +
        (b.fruit + b.fruit2 + b.mast + b.mast2 + b.roots + b.roots2) * HexFauna.PROTEIN_FRUIT
