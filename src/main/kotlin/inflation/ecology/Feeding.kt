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
    fun roots(kg: Float): Float
}

/**
 * A place's food as it is only reckoned on (the residents' [HexFauna.roomFor]): what an animal would get of each, taken
 * off what is there, without eating it.
 */
internal class DryPantry : Pantry {
    var reach = 0f; var grass = 0f; var browse = 0f; var fruit = 0f; var roots = 0f
    override fun grass(kg: Float): Float { val t = min(kg, reach * grass); grass -= t; return t }
    override fun browse(kg: Float): Float { val t = min(kg, reach * browse); browse -= t; return t }
    override fun fruit(kg: Float): Float { val t = min(kg, reach * fruit); fruit -= t; return t }
    override fun roots(kg: Float): Float { val t = min(kg, reach * roots); roots -= t; return t }
}

/**
 * What an animal of a kind eats at a place in a week, kg per m² of it, and what it [got] of nourishment (see [forage]):
 * filled in by [forage], so one can be kept and filled again (the residents graze every place, every kind, every week).
 */
internal class Bites {
    var grass = 0f; var browse = 0f; var fruit = 0f; var roots = 0f
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
    var short = 0f; var room = 0f; var q = 0f; var got = 0f
    /** All it ate, kg per m². */
    val eaten get() = grass + grass2 + browse + browse2 + fruit + fruit2 + roots + roots2
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
 * How animals of [s] eating diet [d] feed where the forage nourishes [q], asking [need] kg per m² this week, from
 * [pantry]: grass and roots (as far as it gets at them under the snow, [underSnow], see [grassUnderSnow]), browse as far as the gut fills at that nourishment, fruit, then
 * switching between them for a shortfall; into [out]. The standing straw is the cured grass, eaten as grass at what it
 * nourishes ([HexPlants.nourishment]). Each food feeds by its energy ([Nourishment]): grass and browse at [q], fruit and roots
 * at theirs, all by [Species.needScale] in kg of its need ([Bites.got], [Bites.short]). The one rule for the residents' week's grazing
 * ([HexFauna.graze]) and for the room they reckon a place has for more of them ([HexFauna.roomFor]).
 */
@IgnorableReturnValue internal fun forage(s: Species, d: Diet, q: Float, need: Float, underSnow: Float, pantry: Pantry, out: Bites): Bites {
    // (Worked in energy, [Nourishment]'s units: a kg of green forage feeds a unit.)
    val want = need / s.needScale
    val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
    // Horses and asses eat as much of poor forage as of good; ruminants eat less of it ([gutFill]).
    val fill = gutFill(s, q)
    // (As the residents: what it cannot get at is a share of the place's food it cannot reach, not a share
    // of its need it goes without; where food is plenty it still eats its fill.)
    val grass = pantry.grass(underSnow * want * fill * d.grass / total)
    val browse = pantry.browse(want * fill * d.browse / total)
    val fruit = pantry.fruit(want * d.fruit / total / Nourishment.FRUIT)
    // (Roots and the mast on the ground lie under the snow as the grass does, and are dug out the same way: boar root less
    // the deeper the snow, keep to oak woods under 40 cm of it and still find acorns there, Chiyojima et al., Mammalian
    // Science 2022; deep snow stops their rooting and starves them, Okarma et al., Acta Theriologica 1995.)
    val roots = pantry.roots(underSnow * want * d.roots / total / Nourishment.ROOTS)
    // Diet switching: a food that fell short is made up from the others: grass and browse as far as the gut holds,
    // fruit and roots as far as the place has them (a hungry animal eats what of its foods it finds; an ostrich out of
    // fruit season picks more shoots and leaves).
    var gap = want - ((grass + browse) * q + fruit * Nourishment.FRUIT + roots * Nourishment.ROOTS)
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
    // But the gut holds only so much bulk a day: on poor forage a ruminant cannot make up by eating
    // more of it (switching shifts what it eats, not how much its gut takes).
    // (Its need is reckoned in forage of middling quality, [HexFauna.GUT_QREF]: on that it fills its need exactly.)
    var room = max(0f, want * fill / HexFauna.GUT_QREF - (grass + browse))
    // (What it switches to nourishes it as well as its gut digests fibre, [Species.fibreDigestion].)
    val qf = q * s.fibreDigestion
    // (But grass the snow buries is made up with browse to at most (1 + SWITCH) times its usual share. A
    // grazer does not turn browser when the snow buries its grass: Przewalski's horses graze all winter in the Gobi, and
    // the khulan's winter food that looked like shrubs in its hair was mostly grass too: Burnik Sturm et al., J Appl Ecol
    // 2017, and the DNA barcoding after. Grass that fell short otherwise, eaten out or poor, is still made up as far as
    // the gut holds: capping that too starved grazers and wild sheep wherever the lean season comes without snow.)
    val buried = (1f - underSnow) * want * fill * d.grass / total * q
    if (gap > 0f && d.browse > 0f) {
        browse2 = pantry.browse(min(min(gap / qf, room), max(0f, gap - buried) / qf + SWITCH * want * fill * d.browse / total))
        gap -= browse2 * qf; room -= browse2 }
    if (gap > 0f && d.grass > 0f && underSnow > 0f) { grass2 = pantry.grass(underSnow * min(gap / qf, room)); gap -= grass2 * qf; room -= grass2 }
    if (gap > 0f && d.fruit > 0f) { fruit2 = pantry.fruit(gap / Nourishment.FRUIT); gap -= fruit2 * Nourishment.FRUIT }
    if (gap > 0f && d.roots > 0f && underSnow > 0f) { roots2 = pantry.roots(underSnow * gap / Nourishment.ROOTS); gap -= roots2 * Nourishment.ROOTS }
    val short = max(0f, gap) * s.needScale
    // Grass and browse nourish fully only when green; fruit and roots keep their value.
    val got = ((grass + browse + grass2 + browse2) * q + (fruit + fruit2) * Nourishment.FRUIT + (roots + roots2) * Nourishment.ROOTS) * s.needScale
    out.grass = grass; out.browse = browse; out.fruit = fruit; out.roots = roots
    out.grass2 = grass2; out.browse2 = browse2; out.fruit2 = fruit2; out.roots2 = roots2
    out.short = short; out.room = room; out.q = q; out.got = got
    return out
}

/** The protein in [b], kg per m², with grass and browse of the protein shares [grass] and [browse]. */
internal fun protein(b: Bites, grass: Float, browse: Float): Float =
    (b.grass + b.grass2) * grass + (b.browse + b.browse2) * browse +
        (b.fruit + b.fruit2 + b.roots + b.roots2) * HexFauna.PROTEIN_FRUIT
