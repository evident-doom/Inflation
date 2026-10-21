package inflation.ecology

/**
 * What a kg of each food nourishes, in one unit for every kind: the kg of green forage, dry matter of quality 1 ([gutFill]'s
 * scale), about [UNIT_KCAL] of metabolisable energy (fresh temperate grass 10-11 MJ/kg DM, so 2.4-2.6 Mcal). On that unit
 * the allometric rule ([Allometry.dailyIntakeKg]) gives every kind the same need for its size, 0.08 M^0.75 a day, some 2.9
 * times Kleiber's basal 70 kcal M^0.75: a hunter's 0.2 M^0.75 kg of prey is that need at [PREY]. A kind's need stays in its
 * own kg ([Species.dailyIntakeKg]); a kg of food feeds [Species.needScale] times its energy here, in kg of that need.
 */
object Nourishment {
    const val UNIT_KCAL = 2500f
    /** Wild fruit and berries, dry: Hadza berries some 320 kcal/100 g dry matter (Murray et al. 2001). */
    const val FRUIT = 1.3f
    /**
     * Wild roots and tubers, dry: much of their bulk is fibre the gut does not use (Hadza tubers, Schoeninger et al. 2001;
     * only a third to two thirds of their glucose absorbed, Schnorr et al. 2015): about 2.5 kcal/g, as middling forage.
     */
    const val ROOTS = 1f
    /** Grain and grass seed, dry: einkorn 369 kcal/100 g (USDA FDC); raw seed's poor digestion is [seedValue]'s. */
    const val GRAIN = 1.5f
    /**
     * A kg of prey killed, the parts left included, as a hunter's need counts it: what keeps the allometric rule's
     * 0.2 M^0.75 kg of prey at the same need as 0.08 M^0.75 kg of forage (0.08/0.2). Some 1 kcal/g: a vertebrate's
     * body is some 1.5-1.8 kcal/g fresh, of which the hunter eats most and digests some nine tenths. Small prey and
     * carrion the same.
     */
    const val PREY = 0.4f
    /** Lean wild meat as butchered: raw deer 120 kcal/100 g (USDA SR "game meat, deer, raw"). */
    const val MEAT = 0.48f
    /** Freshwater fish as caught and cleaned: about 110 kcal/100 g (USDA SR carp 127, pike 88, trout 119). */
    const val FISH = 0.44f
    /** Small finds, eggs, insects, honey: about 150 kcal/100 g as a guess at the mix (eggs 143, honey 304); no source for the mix. */
    const val FINDS = 0.6f

    /**
     * People's need, units a day: a Hadza adult spends some 2,250 kcal a day (Pontzer et al. 2012: men 2,649, women
     * 1,877), below the allometric rule's 1.6 units for 55 kg.
     */
    const val PEOPLE_NEED = 2250f / UNIT_KCAL
}
