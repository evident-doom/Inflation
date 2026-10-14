# Audit 5: people (bands)

Scope: HexPeople.kt, HexCulture.kt, the people parts of HexMeetings.kt, HexGroups.kt and HexLifeAndDeath.kt, Catalogue.HUMAN (Catalogue.kt:538), HexWater.fishFor (HexWater.kt:424), plus the shared constants in HexGroupRules.kt that bands use.
Literature marked "(mem)" is cited from memory and has not been checked.

Weekly order (HexGroups.step, HexGroups.kt:129-190): plan, then meet (where bandsMeet, raids and kite drives happen), then graze, then hunt (hunting, fishing, gathering, larder; sets `fed`), then burnOff, then visitKin, then demography (bandWeek/cultureWeek, then liveWeek: fat draw, births, deaths).
That order matters for several findings below.

---

## 1. Rules

| # | Mechanism | Where | Verdict | Reason |
|---|---|---|---|---|
| 1 | Bands are always "restless" because people never get a `home` (only non-human carnivores settle), so every week they weigh everything within maxDailyTravelKm×SHIFT_DAYS = 80 km, and the "content, stay without weighing" shortcut never applies | HexGroups.kt:277,281-282; init :119; settle HexMeetings.kt:490 | **arbitrary (side effect)** | The rule is meant for homeless packs. Real residential moves are ~10-30 km, 5-15 a year (Binford 1980, Kelly 1995, mem), and the Natufians were semi-sedentary. This resets campWeeks, sheds grinders, empties caches and stops fouling/settled fertility ever building up |
| 2 | Hunting effort per group is `sweptKm2(s)` (360 km²/wk for people), whatever the band size | HexGroupRules.kt:82; HexMeetings.kt:58 | arbitrary | A band of 50 has twice the hunters of a band of 25 but searches the same ground. Need scales with heads; effort does not |
| 3 | Herds met on the way are hunted before resident game (meet() runs before hunt(), and meatMet counts first) | HexGroups.kt:178-184; HexMeetings.kt:54 | arbitrary (ordering) | This is a systematic preference for open-country migratory herds, unrelated to their abundance or return rate. It is a likely driver of the slow overhunting of open-country herds |
| 4 | Resident game is hunted in fixed catalogue order (roe deer, fallow, red deer, ibex, boar, ...) until the meat need is met | HexMeetings.kt:64-65; HexFauna.kt:34 | arbitrary (ordering) | The first kinds in the list take the pressure. The record (diet-breadth / optimal foraging, mem) ranks prey by return rate per handling time |
| 5 | Kills = Poisson(dens × swept × CATCH × catchable × awake × huntEdge), capped by need+1 and by half the local stock | HexMeetings.kt:70-74 | rough | A type-I response with a huge search area and no handling time, so prey equilibrium density is set by the 360 km² sweep. The 0.5-of-stock cap is arbitrary but never binds |
| 6 | Diet shares are fixed (meat .35 / small .12 / carrion .04 / roots .19 / fruit .30). Switching is ≤ (1+SWITCH)× a share, and **people are excluded from switching to plants** (`!s.human`) | Catalogue.kt:543; HexMeetings.kt:133-163, :157 | **arbitrary** | Foragers shift between game and plants with their relative returns (Kelly 1995; Winterhalder, mem). Here meat demand stays constant even where plants abound, so constant hunting pressure is the source of overhunting. And a game shortfall cannot be made up from plants, only from the larder |
| 7 | Hunt stops at need, so there is no "surplus meat" to switch with. `more = min(got-meatEaten, ...)` is nearly always 0 | HexMeetings.kt:60,65,136 | rough | The meat-to-plants switch is effectively dead code for bands |
| 8 | Kite drive: every meeting at a kite place takes 40% of a KITE_QUARRY herd's adults and young, regardless of need | HexMeetings.kt:376-389 | **arbitrary + anachronistic** | Desert kites date to roughly the 7th-4th millennium BC (Neolithic and later, mem), not the Natufian. A 40% take regardless of need means mass killing, with the surplus wasted to carrion. The quarry list is exactly the open-country herds |
| 9 | Kite building: ≥30 adults (15 with FEASTS), cover <0.3, ≥100 herd heads in the place, 10 weeks of work, lasts forever | HexPeople.kt:315-323 | arbitrary | Hard thresholds. Kites never decay. Anachronistic for the period |
| 10 | Chase on meeting a herd: one kill at most, chance CATCH×SURPRISE×catchable×(1-0.5 wary)×huntEdge | HexMeetings.kt:373-404 | rough | Shared predator rule. Bands practise HUNT per chase |
| 11 | Fishing only when big-game meat falls short, at the camp hex, taking a fixed weekly share of each stock (run 30%, river 5%, lake/sea 1%, shellfish 2%) × skill | HexMeetings.kt:105-110; HexWater.kt:424-439 | rough/arbitrary | The fixed-share caps stand in for effort-based CPUE. Fishing replaces only big-game meat, never small game or plants. There is no effort or return cost |
| 12 | Gathering: up to GATHER_SHARE(0.5)×skill×kit (cap 0.9) of each place's standing fruit/root/seed stock per week, spread over every place on the path | HexPeople.kt:147-150; HexMeetings.kt:114-126 | rough | Taking half of all standing plant food per week per place is very high. No time budget (women's foraging hours) |
| 13 | Larder: dried meat (½ the surplus), plus top-up gathering of up to a week's need per week until the cap (LARDER_DAYS × storeShare), at any season. Spoils 1%/wk | HexMeetings.kt:173-191 | rough | Storage is not tied to a harvest season. Together with fat it keeps `fed` high almost always, which masks hunger-driven behaviour (raids, HUNGRY_LEAVE, kin visits) |
| 14 | Moving camp: carry 20 days of food, cache the rest at the old camp (one cache only, 2%/wk spoil), and lose all heavy tools | HexPeople.kt:191-215 | rough/arbitrary | 20 days ≈ 32-37 kg of food per adult on top of up to 140 kg of goods, which is unrealistic. Grinders (bedrock mortars) belong to places, not bands |
| 15 | Materials: weekly kg per adult for stone/wood/resin/fibre from land cover, and hide/bone/sinew as fixed fractions of big-game meat. Goods are capped at 20 kg per adult **per material** | HexPeople.kt:218-261 | rough | Seven materials × 20 kg = 140 kg per adult. Real mobile foragers carry ~10-25 kg of gear in all (mem) |
| 16 | Tools wear linearly, are made at ≤10% of a kit per week, limited by the scarcest material, and making practises the skill | HexPeople.kt:238-256 | rough | Reasonable shape |
| 17 | Invention: chance 0.002/yr × (0.5+craft of the related skill). Teaching: 0.2×(0.5+0.5u) per meeting | HexPeople.kt:244,334-339 | rough | Sickles and grinders are *defining* Natufian traits but start unknown. CLOTHES→STORE in useOf is arbitrary (HexPeople.kt:330) |
| 18 | Skills: practice +2% of the gap per use event, forgetting 0.4%/wk (19%/yr; ORAL ×0.4). Practice fires per kill event (per kind × hex), so HUNT rises faster than the rest | HexPeople.kt:59-62,133; HexMeetings.kt:81,374 | arbitrary | Band know-how sits in elders and lasts decades. Individual hunting skill peaks at 30-40 (Kaplan et al. 2000, mem). The rate depends on the number of events, not on effort |
| 19 | huntEdge = skill × weapons × (1+bow×speed/70) × (1+dogs) | HexPeople.kt:137-138 | rough | Multiplicative and plausible. The 70 km/h is a magic constant |
| 20 | Clothes: feels = t + 10°C×(kit−0.5) below 15°C | HexPeople.kt:160 | rough | Plausible insulation. Clothes need hides, so they depend on big game |
| 21 | campValue = (game + 0.5 × plant stock) × (1 + 0.5 × defence) / (1 + (waterKm/3)²) | HexGroups.kt:233-239 | rough | It ignores fuel, stone, caves/rock shelters, other bands' use of the same country, and the stored-food harvest |
| 22 | burnOff: 10%/wk chance in a dry week (soil <40 mm, >15°C, green <0.4, herb >0.3, not burnt) | HexGroups.kt:245-252 | rough | Fire-stick farming is grounded. The thresholds are arbitrary and the band gets no benefit back other than the herds' draw |
| 23 | Plan: STAY +0.15 − stayCost (fouling vs. BURIAL), UNKNOWN_COUNTRY −0.2, season pull (SKY_LORE ×1.3), crowd −0.5·crowd | HexGroups.kt:355-368; HexPeople.kt:388-392 | rough | Additive scores on mixed scales |
| 24 | Knowing country: path plus neighbours learned; 3 rings known at start; told 4 rings at meetings (chance u); half of what lies >100 km away is forgotten per year | HexPeople.kt:187; HexCulture.kt:140-149,242-247; HexGroups.kt:113-117 | rough | The idea is grounded. The radii are guesses |
| 25 | Births: binomial(mothers, 0.22×fed×settledFertility×(1+0.3·help)); mothers = (adults−elders)/2 | HexLifeAndDeath.kt:186-193 | grounded/rough | An inter-birth interval of ~4.5 yr matches !Kung/Hadza (mem). Linear in fed down to 0 is rough |
| 26 | Settled fertility: up to +40% after 26 weeks in camp with a 30-day larder | HexPeople.kt:373-376 | rough | !Kung settled IBI 4→3 yr (Lee, mem). It never builds up because of rule 1 |
| 27 | **Maturation is memoryless**: binomial(young, years/15) per week | HexLifeAndDeath.kt:246 | **arbitrary** | Exponential stage times mean some "children" become adults in year 1 and others at 40. This distorts dependency ratios, mothers and the life tables |
| 28 | **Becoming an elder is memoryless**: binomial(non-elder adults, years/30) | HexLifeAndDeath.kt:248 | **arbitrary** | 1/30 of 15-year-olds become "elders" in their first year. Elder deaths (+3%/yr) therefore strike people of mean age ~34 |
| 29 | Adult hazard = 0.02 background (×0.85 with MEDICINE) + hunger + cold + disease + thirst + calamities; elders +0.03 | HexLifeAndDeath.kt:227,233-236; Catalogue.kt:552 | **arbitrary (too high)** | Background plus the elder rule alone give e15 ≈ 31 yr (see 4.1). Forager hazard at 15-40 is ~0.5-1%/yr (Gurven & Kaplan 2007, mem) |
| 30 | Death cause is winner-take-all: every death in a week goes to the largest hazard | HexLifeAndDeath.kt:243-245 | **arbitrary (measurement)** | Whenever crowding hazard beats background (~0.017/yr), *all* of that week's deaths read "disease". The "30% crowding sickness" figure is inflated |
| 31 | Crowding sickness = 0.05 × (people within 7 hexes / 0.5 per km²)² × (1+4(1−fed)), for adults and children alike | HexLifeAndDeath.kt:218,270-280; HexPeople.kt:369-370 | **arbitrary** | Quadratic and unbounded in regional density. It does not depend on sedentism, water or sanitation. Crowd diseases need far larger populations than this (mem). Mobility does not reduce it |
| 32 | Camp fouling: children +4%/yr after 8 + 52 weeks in camp (HYGIENE ×0.4, MEDICINE ×0.7) | HexPeople.kt:379-385 | rough | Grounded in the idea of sedentism-borne gut disease, but it overlaps rule 31 and is double-counted with youngMortality |
| 33 | Inbreeding: F += (1−F)/(2·adults)/25 per year; child deaths +0.04×F per year | HexCulture.kt:249; HexLifeAndDeath.kt:203 | rough | The drift formula uses census N, but Ne ≪ N. Bands are treated as closed unless they hold EXOGAMY |
| 34 | Marriage exchange happens only if EXOGAMY is held (0.5 for one side, 1 for both) × 0.5, at meetings; 1-2 adults each way; F mixes toward 0.03×kinship | HexCulture.kt:151-155,186-200 | **arbitrary** | Out-marriage between bands is near-universal among foragers (Hill et al. 2011, mem), so it should not be an optional trait |
| 35 | Elders: help = elders×3/mothers; births ×(1+0.3·help); child deaths ×(1−0.3·help) | HexLifeAndDeath.kt:186-187,193,229 | rough | Grandmother effects are real but modest and mixed (Sear & Mace 2008, mem) |
| 36 | Band split at maxGroupSize 50 (×1.3 with FEASTS) into halves; small bands never merge | HexLifeAndDeath.kt:258-261, :283-313 | **arbitrary** | A fixed cap. Real fission and fusion follow per-capita returns and scalar stress (Johnson 1982, mem). With no fusion, bands of 2-5 adults die out stochastically |
| 37 | Raid only when fed <0.5 and the target's larder >10 days, or on a feud (grudge >1.5); chance 0.3×(1−u)×… | HexCulture.kt:160-183 | **arbitrary** | `fed` is read after the fat draw and larder use, so it is almost never <0.5. (1−u) ≈ 0 for neighbours of the same 150 km block. Feuds need two prior raids, so violence never bootstraps. Most recorded forager violence is revenge or homicide among neighbours of the same language (Kelly 2000; Gat, mem) |
| 38 | Raid deaths: binomial(adults, 0.08×other's share of strength); the stronger side takes 50% of the larder | HexCulture.kt:166-179 | rough | The size is plausible |
| 39 | Grudges: +1 when raided, +0.3 to the raider when it lost people; fade 10%/yr; halved by marriage or by kin feeding | HexCulture.kt:180-181,199,233,250 | rough | |
| 40 | Language: 64 concepts; 3 words aligned per meeting (the bigger band wins); drift 1%/concept/yr | HexCulture.kt:31-35,126-130,251 | **arbitrary (fast)** | Basic vocabulary loses ~14-20% per millennium (mem), about 0.0002/concept/yr. At 50× faster, isolated bands become mutually unintelligible within centuries |
| 41 | Traditions: start ~25% each by lineage hash; random take-up/drop 0.003/yr each; payoff-biased copying 0.1×u per meeting | HexCulture.kt:110,134-137,252-255 | rough | Payoff-biased copying is grounded. But SHARING, MEDICINE, EXOGAMY, ORAL_TRADITION and BURIAL (Natufian) are near-universal, not 25% options. The symmetric flips drift everything toward 50% |
| 42 | Tradition effects: HYGIENE ×0.4; MEDICINE 0.85 background, ×0.7 fouling; SKY_LORE ×1.3; BURIAL −0.1 stay cost; FEASTS ×1.3 size, ½ kite hands; SHARING ×2 gift; ORAL ×0.4 forgetting; TABOO_YOUNG spares all young | various | arbitrary | Every multiplier is a guess, and each one is large |
| 43 | Kin visit: fed <0.6 with less than a week in the larder, so feed from the best larder within 2 rings (larder/(1+r)), BAND_GIFT 30% (×2 SHARING) | HexCulture.kt:208-236 | grounded/rough | Demand sharing is grounded. There is no travel cost or reciprocity ledger |
| 44 | Bands meeting: the poorer is given 30% (60%) of the richer's larder if fed <0.8 (0.9); season news is swapped if 0.1 better | HexMeetings.kt:294-310 | rough | This duplicates rule 43 within the same week |
| 45 | Predators vs bands: kill chance 0.1×(1−CAMPFIRE 0.7)×(1−dog guard)×(1−shelter) if the predator is twice as strong | HexMeetings.kt:437-442 | rough | Rarely fires, because band strength (adults×55×1.5) dwarfs prides. A low share is in line with the record |
| 46 | Prey fighting back: only in chases of migratory herds (0.002×defence×size ratio) | HexMeetings.kt:406-410 | **missing for resident game** | Boar, aurochs and bear are resident, and the resident hunt has no injury risk. Hunting accidents are ~5-10% of forager deaths (mem) |
| 47 | Taming/dogs behind -Dhex.taming (off) | HexPeople.kt:273-299,355 | rough | Natufian dog burials (Ain Mallaha, ~12 ka, mem) argue for dogs being present in this period |
| 48 | Water: people fetch within 10 km (a day's round) | HexLifeAndDeath.kt:209 | rough | 10 km a day carrying water is the outer limit. 3-5 km is typical for a residential camp (mem) |
| 49 | Start: 25 adults, 8 young, larder 30 days, 1/6 of adults elders, crafts 0.6/0.5/0.4, spear 1, net/basket/clothes 0.5 | HexGroups.kt:106-117; HexPeople.kt:167-175 | rough | Children are ~40-50% of a forager band (mem), not 25% |

---

## 2. Constants

Verdicts: G = grounded, R = rough, A = arbitrary. "(mem)" means from memory.

### HexPeople.kt

| Const (line) | Value | Meaning | V | Defensible value / form, basis |
|---|---|---|---|---|
| CRAFT_FLOOR :59 | 0.3 | untrained success share | R | 0.3-0.5. A novice adult forager gets ~30-50% of peak returns (Kaplan 2000, mem) |
| PRACTICE :61 | 0.02 /use | gap closed per use | A | Make it per week of effort (one call per Forage per week), ~0.01/wk, giving ~10 yr to near mastery |
| FORGET :62 | 0.004 /wk | 19%/yr fade | A | Band knowledge decays on a generation scale: ≤0.001/wk (~5%/yr), or tie it to elders |
| MAKE_WEEKLY :64 | 0.1 | kit made per week | R | OK. A full spear kit takes days of work |
| GOODS_PER_PERSON :66 | 20 kg per material | carry cap | A | A cap on the *total* of ~15-25 kg per adult (mem), not per material |
| WEAPONLESS :68 | 0.3 | hunting without spears | R | Fine |
| BOW_GAIN :69 | 0.5 | bow vs fast prey | R | Fine. Pair it with the speed scale below |
| (inline) 70f :138 | km/h | speed at which the bow fully pays | A | Use prey speed relative to human sprint (~30 km/h), or flight distance |
| SICKLE_GAIN :71 | 0.3 | | R | Harvest experiments: sickles roughly double cereal yield per hour over hand-stripping (Harlan 1967, mem), so 0.5-1 |
| GRINDER_GAIN :72 | 0.4 | | R | OK. Pounding acorns makes them edible at all, so for acorns it is more than a gain |
| BASKET_GAIN :73 | 0.3 | | R | OK |
| NET_GAIN :75 | 1.0 | small game and fish ×2 | R | OK (net hunts give large returns, mem) |
| GATHER_SHARE :76 | 0.5 /wk | share of standing plant stock | A | Derive from a gathering time budget: kg/hr × hours (~4-6 h/day women, mem) × gatherers, capped by the stock |
| cap 0.9 :149 | 0.9 | max gathered share | A | Falls away once the time-budget form is used |
| CLOTHES_C :79 | 10 °C | | R | Fur clothing gives 15-20 °C of insulation (mem) |
| (inline) 15f :160 | °C | clothes matter below this | R | OK |
| INVENT :81 | 0.002 /yr | | R | Fine for unknown tools. Sickle and grinder should be known in Natufian start areas |
| TEACH :82 | 0.2 | per meeting | R | OK |
| CARRY_DAYS :84 | 20 d | food carried on a move | A | 3-7 days (mem). Caches take the rest |
| SETTLED_FERTILITY :86 | 0.4 | | R | 0.3-0.4 (IBI 4→3 yr, Lee, mem) |
| (inline) 26 wk, 30 d :375 | | weeks to settled, larder for full effect | R | OK |
| CAMP_FOULING :88 | 0.04 /yr | child deaths | R | Overlaps crowding sickness. Merge the two (see §4) |
| (inline) 8, 52 wk :385 | | onset and ramp of fouling | R | OK |
| HYGIENE 0.4, MEDICINE 0.7 :381,389 | | | A | Small effects, ~0.8-0.9 |
| start crafts 0.6/0.5/0.1/0.4 :172-173 | | | R | OK |
| start kit :174 | | spear 1, net/basket/clothes 0.5 | R | OK |
| making 0.5 :93 | | start making skill | R | OK |
| inbred 0.02 :109 | | start F | R | F ≈ 0.01-0.03 in small populations (mem) |
| stone 0.2+0.8·min(1,5·stone) :224 | kg/adult/wk | | R | OK |
| wood 2·min(1,woody/3) :226 | kg/adult/wk | | R | Fuel wood alone is ~1-3 kg/person/day (mem). It also feeds no fire model |
| resin 0.05 :228; fibre ÷0.5 :229 | | | R | OK |
| hide 0.08, bone 0.1, sinew 0.01 ×meat :233-235 | | | R | Hide is ~7% of live mass (= 0.12×meat at MEAT_SHARE 0.6), bone 10-15% (mem) |
| recipes :43-55 | kg/person | | R | OK. GRINDER 15 kg stone per *person* is high: one quern serves a family |
| wearPerYear :43-55 | 0.05-1.5 | | R | OK |
| heavy reset :200 | | grinders lost on a move | A | Make grinders and bedrock mortars a place feature, like kites |
| useOf CLOTHES→STORE :330 | | | A | CLOTHES should map to HUNT (hides) or a hidework skill |
| UNKNOWN_COUNTRY :342 | 0.2 | | R | OK as a score |
| KNOWN_AT_START :343 | 3 rings | ~30 km | R | 3-5 rings (forager knowledge ~50 km radius, mem) |
| BOLD_DRIFT :346 | 0.0003 /wk | | R | Taming is off |
| TAME :347 | 0.5 /yr | | R | |
| DOG_GROWTH 0.4, DOG_DEATHS 0.1, DOGS_PER_ADULT 0.3, DOG_HUNT 0.2, DOG_GUARD 0.5 :349-353 | | | R | Dogs raised hunting returns in Mayangna/Mayangna-type studies (Koster 2008, mem) |
| (inline) adults/3 :298 | | full dog pack | A | Tie it to the hunters, ~¼ of the band |
| CAMP_LURE :357, /25 :364 | | | R | |
| CACHE_SPOIL :360 | 0.02 /wk | | R | OK (65%/yr) |
| KITE_HANDS :362 | 30 | | A | Anachronistic. Gate kites by era or remove them |
| KITE_WORK :363 | 0.1 | | A | Kites should also decay without upkeep |
| KITE_HERDS :366 | 100 heads | | A | |
| KITE_TAKE :367 | 0.4 | herd share per drive | A | Take only up to need plus what can be dried and stored, and at most once a season |
| cover 0.3 :318 | | kite open-country limit | A | |
| PEOPLE_FULL_KM2 :369 | 0.5 /km² | crowding scale | A | See §4.2 |
| PEOPLE_SICK :370 | 0.05 /yr | | A | See §4.2 |

### HexCulture.kt

| Const (line) | Value | Meaning | V | Defensible value / form |
|---|---|---|---|---|
| Language.CONCEPTS :32 | 64 | | R | OK |
| FORMS :33 | 2^20 | | G | Only needs to make collisions negligible |
| LINEAGES :38 | 16 | | R | OK |
| LINEAGE_KM :39 | 150 km | founding blocks | R | Dialect areas ~100-300 km (mem) |
| GENERATION_YEARS :41 | 25 | | G | 25-30 (Fenner 2005, mem) |
| INBREEDING_DEATHS :47 | 0.04 /F/yr | | G | Bittles & Neel (mem). Use Ne≈N/3 in the F update |
| MARRY :49 | 0.5 | | A | Should be the default for all bands (~0.5-1 per meeting of unrelated bands) |
| OUTSIDER_KIN :51 | 0.03 | | R | OK |
| ADULT_YEARS :57 | 30 | | R | The value is fine. The memoryless use is not (see rule 28) |
| ELDER_DEATHS :58 | 0.03 | | R | The elder total of ~5%/yr matches. The bug is who counts as an elder |
| ELDER_MOTHERS :60 | 3 | | R | |
| GRANDMOTHERS :61 | 0.3 | | R | 0.1-0.2 (Sear & Mace, mem) |
| ELDER_CARE :62 | 0.3 | | R | 0.1-0.2 |
| WORDS_PER_MEETING :64 | 3 | | R | OK |
| WORD_DRIFT :65 | 0.01 /yr | | A | ~0.0002-0.0005 (glottochronology ~80-86% retained per millennium, mem) |
| COPY_TRADITION :67 | 0.1 | | R | |
| TAKE_UP / DROP :69-70 | 0.003 /yr | | A | Use asymmetric rates, or make near-universal practices fixed |
| traditionsAt 25% :110 | | | A | Universal practices should start at 100% |
| RAID :72 | 0.3 | | A | See §4.4 |
| RAID_TAKE :73 | 0.5 | | R | |
| RAID_DEATHS :75 | 0.08 | | R | One or two killed per raid (mem) |
| GRUDGE :77 | 1 | | R | |
| GRUDGE_FADE :78 | 0.1 /yr | | R | |
| FEUD :80 | 1.5 | | A | Two raids needed to start a feud, so violence never starts |
| (inline) 0.3·GRUDGE :181 | | | A | |
| (inline) fed<0.5, larder>10 d :161 | | hunger raid trigger | A | `fed` is post-buffer and almost never <0.5 |
| TOLD_RINGS :82 | 4 | | R | |
| REMEMBERED_KM :83 | 100 km, 0.5/yr :246 | | R | |
| VISIT_FED :85 | 0.6 | | R | |
| VISIT_RINGS :86 | 2 | ~20 km | R | 1-2 days' walk, 30-50 km (mem) |
| FOUL_AVOID :88 | 0.3 | | R | |
| BURIAL_STAY :89 | 0.1 | | R | |
| FEAST_SIZE :91 | 1.3 | | A | |
| (inline) doing(): 60 d :132 | | success yardstick | R | |
| (inline) marryOut min(2, adults/4) :187 | | | R | |
| (inline) ×0.5 grudge halving :199,233 | | | R | |

### People parts of HexMeetings / HexGroups / HexLifeAndDeath / HexGroupRules / Catalogue / HexWater

| Const (line) | Value | Meaning | V | Defensible value / form |
|---|---|---|---|---|
| HUMAN massKg 55 (Cat:539) | | | G | Natufian adults ~50-60 kg (mem) |
| diet .35/.12/.04/.19/.30 (Cat:543) | | | A as fixed shares | Make the shares *preferred* and let return rates drive switching. Natufian isotopes show a mixed diet (mem) |
| groupSize 25, maxGroupSize 50 (Cat:544) | | | R/A | 25 is grounded (Marlowe 2005, mem). The 50 hard cap is arbitrary |
| dailyTravelKm 10, maxDailyTravelKm 40 (Cat:545) | | | R | Forager day ranges are 8-15 km (mem). 40 km is a forced march, not a weekly norm |
| senseKm 10 (Cat:545) | | | A | It drives sweptKm2 = 360 km²/wk. Should depend on hunters and hunt days |
| daysWithoutWater 3, waterFromFood .2 | | | G | |
| youngPerFemale .22 (Cat:547) | | | G | IBI ~4-4.5 yr (mem) |
| yearsToMaturity 15 | | | G | The value is grounded. The memoryless use is not |
| backgroundMortality .02 (Cat:552) | /yr adults | | A | 0.008-0.01 (Gurven & Kaplan 2007, mem) |
| youngMortality .035 (Cat:552) | /yr | | R | Front-loaded in reality (infant ~15-20%) |
| comfortC −5..35 | | | R | |
| speedKmh 45 | | | A | |
| defence .7, stalking .6, ferocity 1.5 | | | R | |
| territoryKm 10, startKm2PerAnimal 40 | | | R | 0.025/km² is low for the Natufian core, OK for the steppe |
| intakeKg 1.6 | kg/day | | R | ~2,500 kcal/day at a mixed ~1.6 kcal/g (mem) |
| PLANT_FOOD_WEIGHT 0.5 (Rules:45) | | | A | Weight by kcal per handling hour, not mass |
| CAMP_SAFETY 0.5 (Rules:46) | | | R | |
| (inline) waterKm/3 (Groups:237) | | | R | 3 km is a typical maximum water-carry radius (mem) |
| LARDER_DAYS 120 (Rules:48) | | | R | OK for Natufian storage |
| LARDER_SPOIL 0.01 (Rules:49) | | | R | |
| START_LARDER_DAYS 30 (Rules:51) | | | R | |
| BAND_GIFT 0.3 (Rules:53) | | | R | |
| BURN_OFF 0.1 (Rules:55) | | | R | |
| inline 15 °C, green .4, herb .3, burnt .1 (Groups:249-250) | | | A | |
| CAMPFIRE 0.7 (Rules:57) | | | R | |
| STAY 0.15 (Rules:71) | | | R | |
| HUNGRY_LEAVE 0.5 (Rules:73) | | | R | Masked by the larder |
| SHIFT_DAYS 2 (Rules:6) | | | A for people | Bands should use dailyTravelKm while fed (see rule 1) |
| CATCH 0.35, SEARCHING 0.3, MEAT_SHARE 0.6 | | | R | MEAT_SHARE 0.6 is fine for people (mem) |
| MAX_CHASES 12 (Rules:76) | | **unused** (`var tries`, Meetings:52) | A | Dead constant |
| MAX_MEETINGS 4 (Rules:88) | | | A | Caps bands meeting each other as well as herds |
| (inline) 0.5×dens×area cap (Meetings:74) | | | A | |
| (inline) young meat 0.3×mass (Meetings:80,395) | | | R | |
| (inline) larder top-up split .4/.3/.3 (Meetings:185) | | | A | Should follow what is in season |
| (inline) dried ½ of surplus (Meetings:178) | | | R | |
| (inline) bandsMeet 0.8/0.9, 30 d, 0.1 (Meetings:297-308) | | | R | |
| (inline) hunger (0.5−fed)×0.3 per wk; young ×1.5 (L&D:198,229) | | | R | Shared |
| (inline) cold ×0.02 per wk (L&D:200) | | | R | Shared |
| (inline) healed 0.85 (L&D:204) | | | A | |
| (inline) mothers = (adults−elders)/2 (L&D:186) | | | R | |
| (inline) 7-hex disc (L&D:273-278) | | | A | Use the band's own hex and camp |
| (inline) elders = adults/6, young = groupSize/3, nearWater <3 km (Groups:106-112) | | | R | |
| run 0.3, riverFish 0.05 (Water:432-433) | /wk × skill | | A | Use effort × catchability × density |
| PEOPLE_FISHING 0.01, PEOPLE_SHELLFISH 0.02 (Water:499-500) | /wk | | A | Same |

---

## 3. Interactions

### Present (and their quality)
- Big-game kills feed hides, bone and sinew, which feed clothes and spears, which feed cold mortality and hunting. Good loop (HexPeople.kt:233-235, 160, 138).
- The plant harvest draws down `fauna.food`, which herbivores eat too. Real competition, but the 0.5/wk share is very high.
- Camp fouling and burial shape the stay-or-move choice. Good idea, but broken by rule 1.
- Larder plus kin visits plus gifts on meeting give hunger insurance. Grounded, but so strong that the hunger signal disappears (see Missing).
- Language understanding gates teaching, telling of country, tradition copying and raids. A good single currency, but the drift is too fast and the raid sign is wrong (below).
- Grudges are softened by marriage and by being fed. Good.
- Taboo on the young lowers take of the reproductive base. Good.
- Kites link herd routes to mass kills. Anachronistic and need-blind.

### Missing (or wrong sign), by importance
1. **Hunting does not respond to prey scarcity through diet choice.** Meat demand is a fixed 35% of need. Plants cannot replace game for people (HexMeetings.kt:157), and fishing only steps in after the hunt. *Why it matters:* it is a type-I predator with constant demand and a 360 km²/wk search, so prey settle wherever kills meet demand. This is the slow overhunting of open-country herds.
2. **No prey switching among game.** Migratory herds go first by event order, then residents in catalogue order (HexMeetings.kt:54,64). *Why:* pressure lands on whoever is first in the code, not on what is abundant or profitable.
3. **Hunting effort does not scale with hunters**, and the whole band (women, elders) counts as the "pack" for strength and fight-back. *Why:* big bands under-hunt per head, and accident risk is wrong.
4. **Crowding sickness vs mobility and sedentism.** Disease depends only on regional density, not on how long a camp is kept, water or hygiene. Camp fouling is a separate child-only term. *Why:* the record ties forager infection burden to sedentism and aggregation (Natufian dental and skeletal stress, mem). Moving does not help here unless the band leaves its neighbours.
5. **Violence vs competition.** Raids trigger only on starvation, which buffers hide. (1−u) makes same-language neighbours peaceful. There is no within-band homicide and no revenge cycle from a first killing. *Why:* this gives 0% violent deaths against a record of 2-15%.
6. **Band fusion is missing** (mergeAndPair skips people, HexLifeAndDeath.kt:288,301). *Why:* tiny bands go extinct instead of joining kin, so extinction is overstated and the population becomes too patchy.
7. **Hunting accidents with resident big game** (boar, aurochs, bear) are absent. *Why:* the missing accident share pushes the cause-of-death mix toward disease.
8. **Age structure does not touch skills.** Band skill does not depend on elders or adult age, and elders do not slow forgetting (only ORAL_TRADITION does).
9. **Storage vs seasonality.** The larder is topped up in any season, so storage never decouples lean and plenty seasons the way Natufian acorn and cereal storage did.
10. **Fire vs camp choice.** Burning gives no camp-value feedback, and wood is gathered but never used as fuel (no fuel cost to staying).
11. **Fishing vs the season.** Spawning runs are taken but camps are not drawn to them in campValue (no fish term).
12. **Exogamy vs inbreeding and alliance.** Marriage alliances should also lower raid chance and raise sharing between the allied lineages. Only grudge halving does this now.
13. **Exchange networks** (shell beads, obsidian) are absent. Stone quality is local only.

---

## 4. Top 10 problems (ranked by distortion)

### 1. Adult mortality is structurally too high (e15 = 16-19 vs 35-40)
Background 0.02/yr (Catalogue.kt:552), plus the memoryless elder transition at 1/30 yr (HexLifeAndDeath.kt:248), plus +0.03/yr for elders, already gives e15 ≈ 1/(0.02+0.033) + 0.625×20 ≈ 31 yr *before* disease, hunger, cold and raids. On top of that the background was meant to include ordinary illness, and disease is added as well.
**Fix (tuning):** set backgroundMortality to 0.01 for people.
**Fix (reshape):** make the elder transition an Erlang chain, either with 2-3 counted adult stages, or simpler, `elders += binomial(adults−elders, years/ADULT_YEARS)` only for the oldest stage. This needs a stage counter on Band, a small **new state**. With background 0.01 and elders at ~0.05 total, e15 ≈ 37-40 before the extra causes.
Do the same staging for children (HexLifeAndDeath.kt:246): infant, child and juvenile stages with front-loaded mortality.

### 2. Cause-of-death accounting is winner-take-all (HexLifeAndDeath.kt:243-245)
In any week where crowding hazard beats background, every death is booked to "disease". The reported ~30% is therefore not a measurement of crowding deaths.
**Fix (reshape, no new mechanism):** split `da`/`dy` multinomially by hazard shares, or book each death to a cause drawn with probability hazard/total. Do this before tuning anything else.

### 3. People never settle: they are perpetually "restless" (HexGroups.kt:277,281-282)
`g.home < 0` is always true for bands, so reach is 80 km/week and the content-stay shortcut is skipped. Everything sedentism-related (campWeeks, fouling, settled fertility, grinders, caches) is crippled, and bands drift fast across herd country.
**Fix (reshape):** for bands, set `restless = g.fed < 0.5 || thirsty || astray` (or treat campHex as home), and use dailyTravelKm×SHIFT_DAYS ≈ 20 km when content.

### 4. Constant meat demand with no switching drives overhunting (HexMeetings.kt:133-163; Catalogue.kt:543)
**Fix (reshape):** remove `!s.human` at :157 so people make up shortfalls from plants up to (1+SWITCH). Also let a band's meat target fall when the last week's hunting return per searched km² is low against the gathering return: target = diet.meat × r_hunt/(r_hunt + r_gather), normalised so it sits at 0.35 at typical returns. The second part is a small **new mechanism**, a diet-breadth rule built only from the existing per-week returns.

### 5. Migratory herds hunted first, residents in catalogue order (HexMeetings.kt:54,64)
**Fix (reshape):** in the resident loop, visit kinds in descending expected kg per chase, i.e. `expected × meat`, and skip kinds whose expected yield per sweep is under a fraction of the best (diet-breadth cut). For herds met, require `meatMet + expected resident return < need`, or weigh a herd's catch against the resident return for the same hex, so herds are not the default first course.

### 6. Kites are anachronistic and need-blind (HexMeetings.kt:376-389; HexPeople.kt:315-323, 362-367)
They take 40% of passing open-country herds per drive, with the surplus wasted.
**Fix (tuning/reshape):** disable kites for a Natufian-era start (a switch like TAMING). Where kites are enabled, cap the take at need plus free larder room (`LARDER_DAYS×storeShare − larder`), allow it once a season per kite, and let kites decay without upkeep.

### 7. Hunting effort is per group, not per hunter (HexGroupRules.kt:82; HexMeetings.kt:58)
**Fix (reshape):** for bands, `sweep = sweptKm2(s) × hunters/6`, with hunters ≈ (adults−elders)/2 (men) and 6 the hunters of a 25-band. Use hunters×massKg as packKg in fightBack and strength. Add the fight-back check to resident kills (HexMeetings.kt:79-85) using `fightBack(ps, hunters×55)` per kill. That reuses an existing rule and gives hunting accidents for boar, aurochs and bear.

### 8. Crowding sickness is quadratic in regional density, sedentism-blind, and counted for adults and children alike (HexLifeAndDeath.kt:218,270-280; HexPeople.kt:369-370)
**Fix (reshape):** merge it with campFouling into one infection hazard ∝ (people in the band's own hex/place) × (0.3 + 0.7·fouled(b)) × (1+4(1−fed)), saturating (x/(1+x)) rather than x². Keep PEOPLE_SICK ≈ 0.01-0.02/yr at saturation for adults and 2-3× that for children. Lower backgroundMortality accordingly so illness is not double-counted.

### 9. Violence never happens (HexCulture.kt:160-183)
`fed` is read post-buffer; same-block neighbours have u≈1, so the raid chance is ×0; feuds need two raids first.
**Fix (tuning/reshape):**
- (a) Trigger raids on *competition*: chance ∝ RAID × (crowd of other bands on the shared country, or per-capita larder imbalance) × (1 − 0.5·kinship), instead of fed < 0.5.
- (b) Use `0.5 + 0.5(1−u)` instead of `(1−u)`, so same-language neighbours still feud.
- (c) Let a single raid with deaths push the grudge over FEUD (GRUDGE 1 → 1.6, or FEUD 1.5 → 0.9), so revenge cycles start.
- (d) Add within-band homicide as a cause in liveWeek (~0.001-0.003/yr adults, scaled by band size over 25). This is a small **new hazard term**.

Target: 2-10% of adult deaths violent.

### 10. Near-universal practices are rare optional traits, and the language drifts 50× too fast (HexCulture.kt:14-24,65,110,151)
EXOGAMY at ~25% makes most bands closed, so inbreeding deaths build. SHARING, MEDICINE and ORAL_TRADITION are similar.
**Fix (tuning):**
- Start EXOGAMY, SHARING, MEDICINE and ORAL_TRADITION at 100% (`traditionsAt`), and BURIAL at 100% for Natufian lineages. Keep the genuinely variable ones (FEASTS, SKY_LORE, HYGIENE, TABOO_YOUNG) random.
- Make TAKE_UP/DROP asymmetric per tradition, or ≤0.0005/yr.
- Set WORD_DRIFT to 0.0003.
- Run the inbreeding update with Ne = adults/3.

### Also worth fixing (cheap)
- GOODS_PER_PERSON should be a total cap (~20 kg), not per material (HexPeople.kt:258-261).
- CARRY_DAYS: 20 → 5.
- Grinders should become a place feature that bands return to (a reshape of the `heavy` rule, HexPeople.kt:200).
- PRACTICE should fire once per Forage per week, and FORGET should be ~0.001/wk.
- Bands need fusion: let mergeAndPair join a band with <8 adults to the nearest band of the highest kinship within VISIT_RINGS. This reuses the merge rule, with **new** handling of how Band data is merged.
- MAX_CHASES is dead (HexGroupRules.kt:76; `tries` unused at HexMeetings.kt:52).
- The larder top-up (HexMeetings.kt:180-189) should only fill from foods in their harvest peak, so storage marks out the seasons.
- Sickle and grinder should be known at start in Levantine lineages.
