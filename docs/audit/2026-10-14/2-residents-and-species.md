# Audit 2: resident plant-eaters and species definitions

Scope: `HexFauna.kt`, `Species.kt`, `Catalogue.kt`, `Niches.kt` (working tree, with uncommitted diet-switching edits). Paths are relative to `src/main/kotlin/inflation/ecology/`. Residents are `herbivores` minus `migratory` (Catalogue.kt:569-579): roe deer, fallow deer, red deer, ibex, wild boar, aurochs, hippo, brown bear, elk and mouflon. Literature values are **from memory** unless a source is given, and should be checked.

---

## 1. Rules (mechanisms)

| # | Mechanism | Where | Verdict | Reason |
|---|---|---|---|---|
| R1 | **"Usual density"**: `1/startKm2PerAnimal`, or **0.5 /km² for every resident** (only the bear sets `startKm2PerAnimal`) | HexFauna.kt:74, :344 | **arbitrary** | Roe deer (25 kg) and hippo (1400 kg) get the same "usual" density. Damuth's rule is already coded in `Allometry.herbivoreDensityPerKm2` (Species.kt:190), and nothing calls it. |
| R2 | **Crowding disease**: `DISEASE·crowd²·years·(1+4(1−fed))`, crowd = own-kind density / (10 × usual) | HexFauna.kt:345-346 | **arbitrary** | Mass-blind threshold: it starts at 5 /km² for all kinds. That binds small deer far below their natural densities (10–30 /km²) and never touches big ones. Only the kind's own density counts, the exponent of 2 is a choice, and the malnourished multiplier double-counts hunger deaths (R7). |
| R3 | **Crowding in appeal/spill**: `/(1 + heads/CROWD)`, CROWD = 10 /km² | :389, :442 | **arbitrary** | Counts heads of the kind's own species only, mass-blind (10 aurochs = 7.5 t/km²; 10 roe = 0.25 t/km²). It stands in for "food per head" without counting the other kinds' demand. |
| R4 | **Forage reach**: 1 %/week of standing herb/shrub (+3 % of trees), 0.2 % when air < 4 °C | :240-241, :460-463, :470 | **rough** | Offtake is tied to standing stock, not growth. Dormancy here is triggered only by cold, but the Levant's lean season is the summer drought; `land.green` exists and is not used here. |
| R5 | **Compensatory intake**: `fill = min(1.25, 1/quality)` | :218-219 | **wrong for ruminants** | Ruminant intake *falls* as digestibility falls because rumen fill limits it (Van Soest, from memory). Only hindgut fermenters (equids) eat more of poor forage, and all residents except boar, bear and hippo are ruminants. |
| R6 | **Proportional sharing + diet switching** (spare split first-come in catalogue order, cap `SWITCH`=1 → ≤2× share) | :242-267, HexGroupRules.kt:14 | **rough** | Proportional sharing is fine. Spare food goes to kinds in list order, so roe deer are always served before aurochs. The 2× cap is the same for every kind, so a strict grazer (hippo, browse 0.05) can switch as freely as an intermediate feeder. Switched food has the same quality whoever eats it, so a grazer gets browse tannins and lignin free of cost. |
| R7 | **Straw fallback** at Jarman–Bell `roughage()` value | :268-274, HexFood.kt:92 | grounded/rough | Scaling with M^0.25 is grounded. **Bug-like:** straw is removed for `wantStraw·rs` (:305) but credited only `min(1, avail·rs)` (:274), so kinds that cannot reach it still deplete it. |
| R8 | **Omnivore animal part** from small prey/carrion, carrion ≤ 50 % of stock/week | :276-288 | rough | The 0.5 cap is arbitrary. Otherwise mass-consistent. |
| R9 | **Fat reserves**: draw (1−f)·days when f < 0.9; gain 0.8 (Jul–Oct) or 0.3 days/day × (f−0.9)/0.1 | :291-294 | **arbitrary, breaks mass balance** | Fat is laid on **without eating anything extra**: a fully fed roe deer fills 18 days of fat in about 4 weeks from nothing. Fattening is tied to calendar months, not to surplus forage. maxFatDays = 8·M^0.25 (Species.kt:153) is grounded (fasting endurance ∝ M^0.25, Lindstedt & Boyce). |
| R10 | **Hibernation**: asleep if weekly T < 0 °C; need = 0; `fed = autumnFed` | :113-114, :229, :275, :299 | **arbitrary** | Sleep costs nothing: no reserve is drawn, and a 6-month winter is the same as a 2-week one. The docstring (Species.kt:121) says sleepers need "a quarter of its food", but the code uses 0. The 0 °C weekly switch flips on and off in mild Levant winters. Real denning is set by food scarcity, photoperiod and pregnancy. |
| R11 | **Births**: `0.5·adults·youngPerFemale·littersPerYear·fed / stepsInSeason` | :335-336 | rough | Fecundity follows *this week's* feeding. In reality it is set by condition at the rut months earlier. Lactation costs nothing, and births create biomass with no food behind it. |
| R12 | **Maturation**: constant weekly share `years/yearsToMaturity` of young become adults | :329, :369 | rough | Exponential maturation means some young breed within weeks. With heavy juvenile mortality, early maturers survive in larger shares, so recruitment is overstated. `yearsToMaturity` acts as *age at first birth*, and several values are set as age at first mating (§3). |
| R13 | **Hunger deaths**: `(0.6−fed)·0.08` per week | :338 | rough | The 0.6 threshold has no basis. Starvation should follow reserve depletion: f < 1 with zero reserve means body protein is being lost. Small kinds should die faster (fasting ∝ M^0.25). |
| R14 | **Cold deaths**: `coldC/10·0.02` per week; young ×1.5; no heat deaths | :341, :355 | rough | Mass-blind: small animals lose heat faster (Bergmann). Heat stress causes no deaths and raises neither water nor food need. The Species.kt:106 doc says the cold "needs more food", but the code doesn't add any. |
| R15 | **Calamities**: fire 5 %/30 % of the burnt share; flood > 2 m drowns 5 %/20 %; snow > 2× limit kills ≤ 10 %/wk; ash > 5 mm | :140-148 | rough | Fire ignores speed, even though the docstring says "the slow". Hiders (roe and fallow fawns) are hit hardest in reality, and that isn't modelled. The 2× snow grace, the 0.1 caps and the young multipliers (×3, ×2) are choices. |
| R16 | **Eagles** take young of kinds ≤ 80 kg adult mass | :352, HexGroupRules.kt:102 | rough | The cut-off should be on *young* mass (golden eagle prey ≤ ~5–7 kg), not adult mass. Boar sits exactly at 80 kg, so it is included. |
| R17 | **Crocodiles** take young of every resident near warm water | :354, HexWater.kt:208-212 | rough | Equal for all kinds. Real risk scales with drinking frequency, i.e. how often a kind comes to the water's edge: hippo, aurochs and boar high; roe, ibex and bear low. |
| R18 | **Adult/young death caps** 0.9 / 0.95 per week | :350, :355 | arbitrary (harmless) | A numerical guard. |
| R19 | **Young: half intake, 0.3 adult mass as carcass** | :226, :367 | rough | Inconsistent: young eat 0.5 but are 0.3 of the mass. Mean juvenile mass depends on years to maturity (hippo young carry ~0.4–0.5 of adult mass over 5–8 years). |
| R20 | **Habitat suitability** `exp(3·(cover·(c−0.5)+rugged·(r−0.3)+wet·(w−0.2)))` | :155-160 | rough | Monotone in cover, so it can't express **edge** preference (roe and fallow peak at mosaic cover). The pivots 0.5/0.3/0.2 and the gain of 3 are chosen. |
| R21 | **Water tether** `reach = travel·daysWithoutWater/4·(1+10·wff²)`, `1/(1+r²)` | :172-177 | rough | The /4 and the 10× are fitted. `waterFromFood` is constant, but preformed water exists only in *green* forage, and need rises in heat. Drinking depletes nothing (§4). |
| R22 | **Food access on crags** `1−0.8(1−climber)·clamp((r−0.4)/0.4)` | HexFood.kt:98-101 | rough | Plausible shape; the constants are chosen. |
| R23 | **Climate fit**: three scales for one thing: `exp(−worst/5)` (init), `exp(−Σ2 months/10)` (bearings), `/(1+d/10)` (appeal) | :97, :103, :387 | arbitrary | These are one physiological quantity, so they should be one function: cost in extra energy or water per °C. |
| R24 | **Bearings value**: `food/(food+0.3)·min(1.5,suits)/1.5·tether·monthFit` | :43-44 | arbitrary | Half-saturation 0.3 kg/m² and the cap 1.5 are chosen. |
| R25 | **Band shifting**: 15 %/wk × relative appeal gain | :393-413, :482 | rough | Seasonal altitudinal migration (elevational) is real. The rate is a choice. Appeal ignores predators and water. |
| R26 | **Spill**: 0.5 %/wk always + 10 % × hunger + 5 % × bearing strength; weighted by food, ease and bearing | :419-456, :484-485, :516-517 | rough | The base 0.5 %/wk (~23 %/yr) of every age leaves a 10 km hex. Real dispersal is mostly natal (subadults at maturity), and its distance scales with home-range size, e.g. √(home range) ∝ M^0.5. Floors of 1e-4 and 0.05 are used. |
| R27 | **Breeding drift**: once a year (month 3), ±1 month if the 3-month fed-sum is better by 0.1 | :120-132, :500-502 | rough | Plastic timing is real; the rate, the 3-month window and the gain are chosen. `fed` is capped at 1, so in good years every window ties and nothing drifts. Gestation is not anchored to rut, and photoperiod is ignored. |
| R28 | **Seed carrying**: `animalSeed = min(1, 0.9·old + fruit/200 kg/km²)` | :307, :493-494 | rough | Only fruit-eaters disperse seeds. Grazers carrying grass and forb seed in their dung ("foliage is the fruit", Janzen) and seeds carried in fur are missing. |
| R29 | **Trampling** 0.1 × resident tonnes | :309-311, :496 | rough | The factor is relative to passing herds, which is fine. There's no hoof-pressure or soil-moisture term. |
| R30 | **Dung** `DUNG·eaten`; carcasses from non-kill deaths | :312, :367 | grounded (with the caveats in R9/R11) | — |
| R31 | **Init**: 0.5 × usual × food/0.3 × suits × climate × tether, then rescaled to `settled-start.properties` | :69-88 | rough/circular | The "settled" densities are outputs of an earlier run of the same rules. Fine as a start; it is not calibration. |
| R32 | **Travel class**: climbers set; non-carnivores > 60 kg swim | Catalogue.kt:582-586 | arbitrary | Mass is not a swimming criterion: roe and red deer, moose and boar all swim well. Roe (25 kg) is put on foot, fallow (65 kg) swims. |
| R33 | **Migratory set** includes mountain gazelle | Catalogue.kt:576 | wrong | *Gazella gazella* is sedentary and territorial, with small home ranges (from memory). It belongs with the residents. |
| R34 | **Niches** activity tables, shelters keyed by name string; `cracksBones` by name | Niches.kt:19-92 | rough / special-case | The shares are plausible, but they should be Species fields. Hard-coded names break if a kind is renamed. |
| R35 | **Niches shelter**: crag `(r−0.3)/0.4`, den `0.8·r/0.5`, water `flood+0.5·(river+lake)` | Niches.kt:81-86 | rough | Chosen ramps. |

---

## 2. Constants

| Name / literal | file:line | Value | Meaning | Verdict | Defensible value/form, basis |
|---|---|---|---|---|---|
| default usual density | HexFauna:74, :344 | 0.5 /km² | Usual density when `startKm2PerAnimal` = 0 | **arbitrary** | `Allometry.herbivoreDensityPerKm2(M)` (Damuth): roe 8.5, mouflon 6, ibex 4.4, fallow 4.2, boar 3.6, red deer 2.2, elk 1.1, aurochs 0.66, hippo 0.42 /km² (computed). Better still, express it in kg/km². |
| `CROWDED` | :465 | 10 | Disease onset at 10 × usual | arbitrary | ~1–2 × the Damuth density, with disease ∝ contact (density) × susceptibility (1−fed). |
| `DISEASE` | :468 | 0.3 /yr | Disease death at crowd = 1 | arbitrary | Calibrate so endemic disease is ~2–5 %/yr at Damuth density (from memory: wild ungulate parasite/disease mortality is a few % in normal years). |
| `MALNOURISHED` | :467 | 4 | Disease ×5 when unfed | rough | Malnutrition–parasite synergy is real; this overlaps hunger deaths. Make it 2 or fold it into hunger. |
| crowd exponent | :346 | 2 | Quadratic | arbitrary | Mass-action gives ∝ density (exponent 1) for per-capita risk. |
| `CROWD` | :480 | 10 /km² | Crowding halves appeal | **arbitrary** | Replace with food per unit demand (all kinds' intake). |
| `REACH` | :460 | 0.01 /wk | Edible share of standing plants | rough | Tie to growth: offtake ≤ ~50–70 % of weekly NPP for grass (Serengeti grazers take ~60 % of grass production, from memory), ~10–30 % for browse. |
| `REACH_DORMANT` | :463 | 0.002 | When T ≤ 4 °C | rough | Scale with `land.green[p]`, which already captures drought dormancy. |
| 4 °C | :240 | 4 | Growing-season switch | rough | Grass growth base is ~5 °C (fine), but moisture is ignored; use green. |
| `BROWSE_OF_TREE` | :470 | 0.03 | Tree biomass that is browse in reach | probably high | Current-year twigs and leaves below ~2 m are ~0.2–1 % of forest stem biomass (from memory). At 20 kg/m² of trees, 0.03 gives 0.6 kg/m² of browse, above typical 0.05–0.2. Suggest 0.005–0.01. |
| `FRUIT_OF_TREE` | :471 | 0.01 | — | **dead code** | Unused; remove. |
| `FOOD_FULL` | :473 | 0.3 kg/m² | Food that carries usual density (init only) | rough | Start only, low impact. |
| `HUNGER_DEATHS` | :475 | 0.08 /wk per unit below 0.6 | Starvation | rough | Death when reserve = 0 and f < ~0.8, rate ∝ M^−0.25. |
| 0.6 | :338 | — | Hunger threshold | arbitrary | See above. |
| `COLD_DEATHS` | :476 | 0.02 /wk per 10 °C | Cold | rough | Make it energy cost: raise need by ~2–4 % per °C below the lower critical temperature, and let hunger kill. |
| `EXTINCT` | :478 | 1e-4 /km² | Local extinction | fine | ~0.1 animals in 10 km² of a place; plausible numerical floor. |
| `BAND_SHIFT` | :482 | 0.15 /wk | Band moves | rough | Elevational migrants move in days, so it can be higher in autumn snow; fine. |
| `SPILL_BASE` | :484 | 0.005 /wk | Background emigration | arbitrary | Natal dispersal: the share of young maturing × dispersal probability (~0.5 of males), × P(leave the 10 km hex) ∝ dispersal distance/hexKm. |
| `SPILL_HUNGER` | :485 | 0.1 /wk at fed 0 | Hunger emigration | rough | Plausible. |
| `SPILL_MISFIT`, `BEARING_SPILL` | :516-517 | 0.05, 3 | Bearing pull | arbitrary | Tune together. |
| 1e-4, 0.05 floors | :442 | — | Weight floors | numerical | OK. |
| `START_SHARE` | :487 | 0.5 | Start at half | fine (init) | — |
| `GUT_FILL` | :491 | 1.25 | Max intake/need | rough for equids; **wrong direction for ruminants** | Ruminants: fill ≤ 1, falling with quality. Hindgut: up to ~1.5. |
| `SEED_KG`, `SEED_KEEP` | :493-494 | 200 kg/km², 0.9/wk | Seed rain | rough | — |
| `RESIDENT_TRAMPLE` | :496 | 0.1 | vs passing herd | rough | — |
| `HIBERNATE_C` | :498 | 0 °C | Sleep switch | arbitrary | Enter the den when forage < need and T < ~5 °C for several weeks; leave when green-up starts. |
| `FED_MEMORY` | :500 | 0.1 | Month memory | rough | ~3-year memory over 4 weeks a month; fine. |
| `BREED_SHIFT_GAIN` | :502 | 0.1 | Drift threshold | arbitrary | Use condition (reserve), not capped `fed`. |
| `FIRE_ADULT`, `FIRE_YOUNG` | :504-505 | 0.05, 0.3 | Fire kill share | rough | Large mammals ~0–2 %; neonate hiders up to ~20–30 % (from memory). Scale by speed and by hider/follower young. |
| `FLOOD_DROWN_M`, `FLOOD_ADULT`, `FLOOD_YOUNG` | :507-509 | 2 m, 0.05, 0.2 | Flood drowning | rough | Make swimmers (`Travel.SWIM`) lower. |
| `SNOW_DEATHS` + 2× grace + 0.1 cap + ×3 young | :511, :145 | 0.02 | Deep snow | rough | Snow should raise movement energy cost (∝ sinking depth/chest height) and cut forage; let hunger do the killing. |
| `ASH_HARM_MM`, `ASH_DEATHS`, /50, cap 0.1, ×2 | :513-514, :147 | 5 mm, 0.02 | Fluorosis | rough | — |
| 0.3 (bearings food half-saturation), 1.5 (suits cap) | :43 | — | — | arbitrary | — |
| 0.7 / 0.5 / 0.8 | :51, :53, :55 | — | Initial fedByMonth/reserve/autumnFed | fine (init) | — |
| 0.2 | :78 | — | Young:adult at start | rough | ~0.3–0.6 just after the birth season. |
| /5, /10, /10 | :97, :103, :387 | °C scales | Three climate penalties | arbitrary | Unify (R23). |
| 3-month window | :125 | — | Birth + 2 months | rough | Use lactation length (deer ~3–4 months, hippo ~8–12). |
| 0.1, 2, ×3 (snow); /50, 0.1, ×2 (ash) | :145, :147 | — | — | arbitrary | — |
| pivots 0.5, 0.3, 0.2; `HABITAT` 3 | :159, :462 | — | Habitat fit | rough | — |
| /4, 10 | :174 | — | Water reach | arbitrary | reach ≈ dailyTravel × daysWithoutWater / 2 (out and back), × green-adjusted preformed water. |
| 0.5 young intake | :226 | — | — | rough | Use mean juvenile mass^0.75 over the juvenile period. |
| 0.3 young carcass | :367 (also HexMeetings) | — | — | rough | Same as above. |
| 0.3 (fat gain off-season), months 7..10, 7..11, 0.2 | :294, :299 | — | Fattening | **arbitrary** | Surplus intake × ~0.6 efficiency; no calendar. |
| `AUTUMN_FAT`, `WELL_FED` | HexGroupRules:10, :12 | 0.8, 0.9 | Fat gain rate, threshold | arbitrary | See R9. |
| `SWITCH` | HexGroupRules:14 | 1 | Max ×2 share | rough | Scale by feeding type: intermediate feeders ~1.5–2, strict grazers/browsers ~0.3–0.5, with a digestibility penalty on off-type food. |
| 0.5 | :336 | — | Female share | rough | Polygynous ungulates ~0.55–0.65 female adults. |
| 0.9, 0.95 | :350, :355 | — | Death caps | numerical | OK. |
| 1.5 | :355 | — | Young × hunger/cold | rough | Plausible. |
| 0.5 carrion cap | :285 | — | — | arbitrary | — |
| 0.7 / 0.3 | :304 | — | Browse from shrub vs tree | arbitrary | Split by what's available (rb weights), not fixed. |
| 0.3 | :388 | — | Snowed appeal | arbitrary | Use food actually reachable under snow. |
| `EAGLE_PREY_KG` | HexGroupRules:102 | 80 kg adult | — | rough | Young mass ≤ ~6 kg at birth to a few weeks. |
| `CROC_RATE`, `CROC_COLDEST`, 16 °C | HexWater:516-517, :210 | 0.1/yr, 12 °C, 16 °C | — | rough | × drinking frequency. |
| `DRY_VALUE` | HexLand:439 | 0.65 | Dry-forage nourishment | rough | Dry Mediterranean grass is ~45–55 % digestible vs ~70 % green (from memory), i.e. ≈0.7 relative. OK, but not size-dependent. |
| `ROUGHAGE_VALUE`, 0.75 cap | HexFood:85, :92 | 0.4 @100 kg | — | grounded shape | — |
| access 0.8, 0.4, 0.4 | HexFood:100 | — | Crag access | rough | — |
| 8·M^0.25, ×4 hibernator | Species:153 | — | Fat days | grounded / rough | Hibernators carry ~30–40 % body fat, about 100–150 days at 25 % metabolism, so ×4 is OK *if drawn at 0.25*. |
| 10 °C coat, `COAT[]` | Species:161, :171 | — | Winter coat | rough | Plausible. |
| 0.065·M^0.75 | Species:183 | kg DM/day | Herbivore intake | **low** | Field metabolic rate ≈ 2–3 × basal metabolic rate. With 8–9 MJ ME/kg DM that gives ≈0.08–0.10·M^0.75 for free-living ruminants. Equids eat ~1.3–1.5× a ruminant of their size (from memory). |
| 0.2·M^0.75 | Species:183 | carnivores | — | grounded-ish | — |
| 10^4.23·M_g^−0.75 | Species:190 | Damuth | — | grounded | **Unused**; should feed R1. |
| defaults: comfort −10..30, speed 60, foresight 0.4, territoryKm 3, fearOfPeople 0.7, boldShare 0.03 | Species:102-142 | — | — | arbitrary defaults | Make them required, or derive them. |
| 60 kg swim threshold | Catalogue:586 | — | — | arbitrary | Swimming ability as a per-species flag. |
| Niches ACTIVITY tables | Niches:19-44 | — | — | rough | Plausible; move to Species. |

**Dead or unused species fields** (grep found no use outside Catalogue): `flightKm`, `dangerToHumans`, `products`, `fearOfPeople`, `territoryKm` (HexGroups has its own `territoryKm()` function, :412), plus `FRUIT_OF_TREE`. These are values that look tuned but do nothing.

---

## 3. Species (values that look wrong against biology, from memory)

**General.** `yearsToMaturity` is used as *age at first birth* (HexFauna:336/369: adults breed at once). Several values below are age at first mating, about one gestation too early.

| Species | Parameter | Now | Real (from memory) | Note |
|---|---|---|---|---|
| **All residents** | usual density | 0.5 /km² | Damuth: roe 8.5 … hippo 0.4 | R1 |
| **All** | intake | 0.065 M^0.75 | ~0.08–0.10 M^0.75 free-living | Species:183 |
| Roe deer | yearsToMaturity | 1 | First fawns at ~2 yr (mated at 14 months, delayed implantation) | → 2 (or 1.9) |
| Roe deer | comfortC upper | 25 | *C. c. coxi* lived on Carmel and in Galilee (summer means 26–28) | → 28–30 |
| Roe deer | backgroundMortality | 0.12 | Prime-age non-predation ~0.05–0.08 | → 0.07 |
| Roe deer | habitat | cover 0.6 | Edge species: peaks at intermediate cover | needs an edge term (R20) |
| Roe deer | travel | FOOT | Good swimmer | R32 |
| Fallow (Persian) | comfortC upper | 28 | *Dama mesopotamica* lives in Khuzestan riverine forest, summer means 33–36 | → 35 |
| Fallow | yearsToMaturity | 1.5 | First birth at ~2 yr | → 2 |
| Fallow | massKg | 65 | Males ~90–100, does ~45–55, so mean ~65–70 | OK |
| Red deer | comfortC upper | 25 | Mediterranean and N-African red deer bear 28–30 | → 28–30 |
| Red deer | snowLimitMm | 300 (≈1 m snow) | Struggles above ~40–60 cm (~150–200 mm SWE) | → 180 |
| Red deer | yearsToMaturity | 2.5 | Hinds first calve at ~3 (2–4) | → 3 |
| Red deer | speedKmh | 70 | ~50–65 | → 60 |
| Ibex | definition | Mixed Nubian/Caucasian (60 kg, −12..30) | Nubian ibex in the Levant: males 60–70, females 25–30 (mean ~45); hot Negev and Dead Sea (summer means 33–35), winters rarely < 0 | Pick Nubian: mass 45, comfort 0..38, snowLimit ~80, youngPerFemale 0.9 (twins rare) |
| Ibex | daysWithoutWater 3 / waterFromFood 0.6 | — | Nubian ibex drinks every 1–3 days in summer, rarely in winter | OK; should vary with green (R21) |
| Wild boar | youngPerFemale × littersPerYear | 4 × 2 = 8/yr | Litter ~4–6; second litter rare except after mast; mean ~4–5 per adult sow per yr | → youngPerFemale 5, littersPerYear 1. Let mast (fruit/roots fed) drive it. |
| Wild boar | comfortC upper | 30 | Mesopotamian marshes, Jordan valley (summer means 33–35) with wallows | → 34 |
| Wild boar | snowLimitMm | 200 (~65 cm) | Snow > ~30–40 cm limits boar (Melis et al.) | → 100–120 |
| Wild boar | massKg | 80 | Near-Eastern boar: males 100–150, sows 60–80 | → 90 (affects eagle cut-off, which sits exactly at 80) |
| Aurochs | comfortC upper | 28 | Ranged through Egypt, Mesopotamia and the Jordan valley (summer means 30–34) | → 33 |
| Aurochs | snowLimitMm | 300 | Cattle crater poorly (bison better); ~150–250 | → 200 |
| Aurochs | intake | 9.3 kg | Wild cattle ~1.5–2 % body weight, i.e. 11–15 kg DM | Covered by the intake fix |
| Hippo | comfortC lower | 14 | Levant coastal rivers (winter means 12–13) held hippos into the Iron Age; water buffers cold | → 10, and count water temperature, not air |
| Hippo | yearsToMaturity | 5 | Wild females first calve at ~9–10 (Uganda); 3–4 only in captivity | → 8 |
| Hippo | fibre/intake | — | Foregut fermenter with *low* metabolic rate; eats ~1–1.5 % body weight | Intake ×0.7 vs the formula once it is raised |
| Hippo | SWITCH | ×2 browse | Hippos barely switch | Feeding-type-scaled switch |
| Brown bear | hibernation | Free sleep | Loses 20–40 % body mass over 3–6 months | R10 |
| Brown bear | birthMonth | 2 (Mar) | Cubs born in the den Jan–Feb | → 0–1; tie births to the den |
| Brown bear | youngPerFemale | 1 | 2 cubs every 2–3 yr, so 0.7–1.0 | → 0.8 |
| Brown bear | yearsToMaturity | 4 | First litter at 4–6 | → 5 |
| Elk (moose) | snowLimitMm | 900 (~3 m) | Moose are hindered at ~70–100 cm depth (~250–350 SWE) | → 300 |
| Elk | yearsToMaturity | 2 | First calf at 2–3 | OK |
| Elk | roots 0.15 | — | Aquatic plants in summer only (sodium) | OK as "roots" proxy, but not seasonal |
| Mouflon | snowLimitMm | 200 | Very snow-sensitive; > 15–25 cm hinders (≈60–80 mm SWE) | → 80 |
| Mouflon | climber | Yes (CLIMB) | Rolling hills; less of a crag-dweller than ibex | Its rugged 0.6 already gives access; CLIMB travel is generous |

Migratory and others (outside the resident engine, flagged for the record):

- **Gazelle:** mountain gazelle is sedentary (R33); comfort low 5 is too high for Golan winters (→ 0).
- **Reindeer:** snowLimit 800 → ~300.
- **Lion, leopard, hyena, jackal, cheetah** have `youngPerFemale = 0` and use `packLitter`. Fine for the agent model.
- **Ostrich** `youngPerFemale = 3` survivors with `youngMortality 0.7`: double-counts early chick loss if 3 is already "chicks that live through their first weeks".

---

## 4. Interactions

**Present:**

- **Plants:** grazing, browsing and rooting stocks; lawn effect, browse suppression and rooting effects on recruitment via `land.grazing`/`browsing`/`rooting`; trampling; seed carrying; grazing lowers fire fuel.
- **Food stocks:** fruit, roots and straw.
- **Small life and soil:** dung, carcasses, small-prey/carrion take by omnivores.
- **Predators:** kills in `HexMeetings.hunt`; eagle and crocodile risk rates.
- **Migratory herds:** indirectly, through the shared plant stocks.
- **Weather:** snow, cold, forecasts; band shifting by appeal.
- **Disturbance:** fire, flood, ash.
- **Water:** tether.

**Missing (each matters for niche structure or regulation):**

1. **Fear of predators.** Residents don't shift habitat, group or lose feeding time where predators are. Predator-driven habitat partitioning (gazelle in the open, deer in cover, ibex on crags) is what separates niches. Without it, only food and habitat weights do.
2. **Condition-dependent predation.** `catchable` (HexMeetings:27-40) ignores `fed` and reserves, and young are taken pro-rata, not preferentially. Real predation falls on the starving, the young and the old, so it is partly compensatory. Here it is fully additive, which overstates predator control in good years and understates it in bad.
3. **Disease across species and from water.** Pathogens shared among ruminants, aggregation at drying water holes and links to carcass load are all absent. Disease is purely own-density, so it acts as a per-species cap rather than an ecological interaction.
4. **Water as a resource.** Drinking depletes nothing, and herds don't foul or compete at water. Heat doesn't raise water need, and `waterFromFood` doesn't fall as forage dries. In the Levant summer this is *the* constraint separating oryx, ibex and gazelle from aurochs and boar.
5. **Size-dependent forage quality (Jarman–Bell) for fresh forage.** Only straw uses it. Small selective feeders (roe) should need green or high-quality food; big grazers tolerate dry grass. Without it, roe deer compete with aurochs on equal terms.
6. **Grazing facilitation and interference.** Large grazers open the sward for smaller ones (Bell 1971 grazing succession). Spare food is allocated in list order, not by competitive ability.
7. **Costs of reproduction and growth.** Births, growth and fat appear without food (R9, R11). Mass is not conserved, which breaks the closed-system requirement, and populations recover faster than food allows.
8. **Temperature costs on energy and water.** Cold and heat beyond comfort should raise intake or water need and cut foraging time (heat). Today they only kill directly or lower appeal.
9. **Age and sex structure.** There's no senescence, rut or male-biased mortality. Harvest by people and predators (males in rut, old animals) can't differ from natural death.
10. **People.** `fearOfPeople` isn't read by residents, so there is no avoidance of camps. People's burning creates green flush, but residents feel it only through plant growth.
11. **Mast and breeding.** Boar and bear fecundity should track fruit and roots (acorn years). There is only the weekly `fed` link.
12. **Snow on browse.** Deep snow hides grass only. Low shrubs are buried too, and there's no crust effect or movement cost.
13. **Spill dispersal** doesn't come from maturing young, and isn't pulled by empty suitable ground beyond the 6 neighbours (colonisation is slow, as it should be, but at an arbitrary rate).

---

## 5. Top 10 problems (ranked by distortion)

1. **Mass-blind "usual density" drives disease and crowding** (HexFauna:74, :344-346, :389, :442). 0.5 /km² for every resident and CROWD = 10 /km² make disease the hidden cap: it binds roe, mouflon and ibex far below natural density and never binds aurochs or hippo. *Fix (tuning/reshape):* use `Allometry.herbivoreDensityPerKm2(massKg)`, which already exists, as `usual`. Make disease ∝ density/usual (exponent 1) × (1 + 2(1−fed)), calibrated to ~3 %/yr at usual density. Replace `/(1+heads/CROWD)` with food ÷ total intake demand of all kinds in the place.
2. **Mass not conserved: free fat, free births, free growth** (:291-294, :336, :369). *Fix (reshape):* let animals eat above need up to the gut limit when food is spare, draw the surplus from the plant stocks, and convert it to fat days at ~0.6 efficiency, with no calendar months. Add a lactation demand (+50–100 % intake for mothers through the lactation window) to `need`, so births compete for food. This is a modest new term in an existing rule.
3. **Compensatory intake of poor forage is backwards for ruminants** (:218-219). *Fix:* a digestive-type field (ruminant / hindgut / simple stomach; **new parameter**, small). Ruminants get fill = min(1, quality/0.8); equids get the current rule.
4. **Forage reach ignores drought dormancy** (:240). The lean season in the Levant is summer, yet 1 % stays available whenever T > 4 °C. *Fix:* `reach = REACH_DORMANT + (REACH − REACH_DORMANT)·land.green[p]`, ideally tied to the weekly growth flux.
5. **Hibernation is free and calendar-blind** (:229, :275, :113). *Fix:* asleep kinds draw reserve at 0.25 × need per day (matching the docstring). Hunger deaths come when the reserve runs out. Enter the den on low forage plus cold over several weeks, not a 0 °C week.
6. **Intake allometry is maintenance-level** (Species:183). 0.065 M^0.75 understates free-living demand by ~30–50 %, so grazing pressure on plants is too low throughout the closed system. *Fix:* 0.09 M^0.75 for ruminants, ×1.4 for equids, ×0.7 for hippo. Recalibrate REACH after.
7. **Thermal limits set for temperate Europe** (Catalogue: fallow 28, aurochs 28, red deer 25, roe 25, boar 30, hippo low 14, ibex −12..30). This pushes Levant natives out of the warm lowlands where the record puts them. *Fix:* the values in §3.
8. **Predation and fear disconnected from condition and behaviour** (HexMeetings:27-40; HexFauna appeal :382-390). *Fix:* tune `catchable` × (1 + k(1−fed)) and favour young. Add last week's kills per km² (already counted) as a risk term in `appeal` and `bearings`, scaled by (1 − defence) and offset by `Niches.shelter`. This is a **new link** but uses existing data.
9. **Breeding parameters** (boar 8 young/yr; age at first birth set as age at first mating for roe, fallow, red deer and hippo; exponential maturation). *Fix:* the §3 values. Optionally use a two-stage juvenile class (yearling, subadult) to cut the exponential spread, which is a **small new mechanism**.
10. **Water is constant and costless** (:172-177; Catalogue `waterFromFood`; croc risk :354). *Fix (tuning):* effective preformed water = waterFromFood × green, and need ×(1 + 0.03 per °C above comfort). Replace the /4 and 10× with an out-and-back reach (travel × days/2). Scale crocodile risk by visits per week (1/daysWithoutWater × (1 − wff_eff)). Water stocks being drunk down would be a **new mechanism**.

Further, smaller fixes:

- Snow limits for elk, reindeer, red deer, mouflon and boar (§3).
- Swimming as a flag, not > 60 kg (Catalogue:586).
- Move mountain gazelle to the residents.
- Unify the three climate scales (R23).
- Straw consumed by kinds that can't reach it (:305 vs :274).
- Spare allocated in catalogue order (:252).
- Remove the dead fields `flightKm`, `dangerToHumans`, `products`, `fearOfPeople`, `territoryKm` and `FRUIT_OF_TREE`, or wire them in.
