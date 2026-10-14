# Audit 3: small life and water (HexSmallLife.kt, HexWater.kt)

S = `src/main/kotlin/inflation/ecology/HexSmallLife.kt`, W = `src/main/kotlin/inflation/ecology/HexWater.kt` (line numbers from the current working tree).
Step = 6 days; `years` = 6/365 ≈ 0.0164. Literature values are **from memory** unless stated, and are order-of-magnitude anchors, not citations.

---

## 1. Rules

### HexSmallLife

| Mechanism | Where | Verdict | Reason |
|---|---|---|---|
| Litter decomposition, Q10≈2 temperature, linear soil-moisture factor | S:173-179 | grounded | Q10 = 2 (0.069 = ln2/10) is the standard soil-respiration form; k ≈ 1.2/yr at 15 °C and moist sits inside the Mediterranean litter range of 0.3-1.5/yr. |
| Humification 30 %, OM turnover 5 %/yr | S:180 | rough | The form is right (a two-pool, CENTURY-like model). The 70 % that is respired leaves as CO2 (acceptable), but the N and P in it vanish too (see §3). |
| Organic fertility = 0.3·OM/(OM+4) | S:181 | arbitrary | A hand-set saturating curve on the OM *stock*. Plants actually feed on the mineralisation *flux* (0.05·OM/yr). |
| Soil fauna = 20·decomposition rate | S:182 | rough | A diagnostic only: no stock, eats nothing, feeds nothing. |
| Carrion rot: linear `0.07·warm·days`, min 1 | S:186 | arbitrary | At 25 °C 84 % goes per week, and at ≥28 °C 100 %. The comment says "half in ten days". It is linear, not `1-exp(-k·days)`. |
| Carrion: 30 % to litter (incl. 20 % bones), 70 % vanishes | S:187 | rough | Respiration is real, but the maggots, blowflies and beetles that do the work never get the mass. |
| Rotting happens **before** vultures feed in the same step | S:184-189 vs S:254 | arbitrary (ordering bug) | Vultures and mammal scavengers only see what survived a week of rot (see §5 #1). |
| Bones weather `years/2` per step | S:191 | rough | Gives an e-folding of about 2 yr. Arid-land bones last 5-15 yr. |
| Plant-eating insects: logistic with ceiling 2000·food/(food+0.15), growth 15/yr, deaths 2/yr, heat-drought and cold die-off, clamp 3000 | S:195-209 | rough/arbitrary | Their intake is real (they consume plants, frass goes back). The ceiling and the clamp are hand-set caps, and growth is not tied to intake. |
| Insects eat 25 %/day of their mass from herbs and shrubs, 50 % back as frass | S:205-206 | rough | Real values are 10-30 %/day fresh mass. Assimilation is 30-40 %, so roughly 60 % of intake becomes frass, not 50 %. |
| Biting insects: +60·days·max(wetland,0.3), cap 500, ×0.7 per step | S:211-212 | arbitrary | They have no food and no matter accounting. The ×0.7 decay ignores step length, and they affect nothing (only the viewer and history read them). |
| Rodents: logistic with births 5/yr·food saturation, deaths 1.2/yr (+3/yr in cold or snow), ceiling 600·food/(food+0.3), clamp 3000 | S:214-220 | rough | Death rates are plausible. The ceiling and clamp are hand-set, and growth is not linked to what they eat. |
| Rodents eat 10 %/day of herbs | S:221 | grounded | Small herbivorous rodents eat 10-20 %/day of their fresh mass. They eat herbs only; seeds and fruit are not separated. |
| Herps: growth on insect density, death 1/yr, ceiling 80·food, clamp 200; eat 5 %/day of their mass in insects | S:223-232 | rough | Growth depends on insect *density*, not on intake. 5 %/day is about 2-3× real for reptiles (1-3 %/day when active). The eaten insects are not egested. |
| Fire kills 80/70/50/50 % (insects/herps/rodents/pollinators) on the burnt share | S:234-238 | rough | Plausible ordering by mobility. |
| Pollinators: calendar months 3..7, flowers reduced by grazing, logistic ceiling 50·flowers, clamp 100 | S:240-248 | arbitrary | Flowering should follow plant phenology (temperature and rain). Pollinators eat nothing, so nectar and pollen are not a flow. |
| Pollination smoothed toward pollinators/20; fruit set 0.3 + 0.7·pollination | S:249, S:392 | rough | A 30 % wind and self-pollination floor is defensible. Pollination never decays outside the flowering season. |
| Hares, hyrax, 6 birds, tortoises: `logistic()` toward hand capacities, **no food intake** | S:262-278, S:133-153 | arbitrary | Mass is created from nothing, and they do not compete with the herds for forage (see §5 #3). |
| `logistic()`: deaths doubled above k, k floor 0.01 | S:327-332 | arbitrary | The ×2 is ad hoc. The negative growth term above k removes mass that never reaches `smallDead`. |
| Migrants leave, are held "away", and 70 % come back spread by capacity | S:157-168, S:270-271 | arbitrary/bug | There is no breeding while away. The greylag goose breeds in months 5..6 but is only present in 10..3, so it **never breeds** (S:416) and declines geometrically. |
| Foxes and wildcats: ration 0.15/day, take ≤10 % of prey per week, logistic cap 0.02·prey | S:281-289 | arbitrary | The ration is about 2× real. Both the prey cap and the K cap are hand-set. Insects count as prey but are never removed, so foxes get phantom food. |
| Eagles: an index 0..1 capped by crags; prey hyrax, hares, chukar and dove; ration 0.01 kg/km²/day; +0.3 "fawn" subsidy | S:291-306 | rough/arbitrary | The crag-limited nest cap is defensible. The +0.3 subsidy is phantom food, while fawns killed through `eagleRisk` become carrion (HexFauna.kt:352, 367), so they are counted twice. Eagle deaths are uncounted. |
| eagleRisk = 0.15·eagles·open per year on young ≤ EAGLE_PREY_KG | S:315 | rough | It is not tied to eagle need or numbers in kg. |
| Vultures: colonies fixed at init, 0.5 kg/day, chick in months 1..2, mortality 0.08 + 0.4·hunger, cap 2000, gone below 2 for good | S:338-353 | rough | Ration and survival are griffon-like. Colonies cannot be refounded, the cap is hand-set, and the birds' deaths are not added to carrion. |
| catchSmall: proportional over rodents, hares, hyrax, birds, herps and tortoises, ≤50 % of the total per call | S:363-375 | arbitrary | The 50 % is per call, so repeated callers can strip everything. It ignores the predator's own capability. Tortoises are "out" without the soil check used in S:277. Insects are excluded (bears, badgers and people eat them). |
| scavenge / crackBones: take up to what was asked | S:386, 389 | grounded | Simple mass-conserving removal. Callers cap it at 50 % (HexFauna.kt:285, HexMeetings.kt:98). |
| Spread: every 5 steps each field moves 20 % toward the neighbour mean | S:255, S:394-402 | arbitrary | It is not area-weighted, so it does not conserve mass. All taxa spread at the same rate (tortoises like geese). Eagles spread into places with no crags, then freeze there because their dynamics are gated by `eagleCrags[p] > 0` (S:291). |

### HexWater

| Mechanism | Where | Verdict | Reason |
|---|---|---|---|
| Static nutrient `supply` from depth, river-mouth meanFlow and lake type | W:119-128 | rough/arbitrary | It is set once from the long-term mean flow. It does not respond to land runoff, fire ash or soil loss. Closed lakes get 0.2, so hypersaline Lake Lisan grows fish. |
| Ekman upwelling: wind turned 90° right, lagged 3 weeks | W:226-232 | grounded | Correct physics for the northern hemisphere. The wind scale of 8 m/s is reasonable. |
| Mixing step function 0.25/0.08/0.02 per day by water temperature | W:234 | rough | Captures the stratification idea, but it should be continuous and wind-dependent. |
| NPZ daily loop: µmax 0.9/day, zoo ingestion 0.6/day, assimilation 0.3, 50 % of waste recycled | W:240-247 | rough | The rates are within textbook ranges. The other 50 % sinks out of the system (see §3). |
| Light = day length at fixed 38°N /12 | W:222 | rough | The Levant is 30-34°N. It ignores cloud and turbidity. |
| Water temperature = climate monthly normals | W:53, 107, 233 | rough | It ignores the week's weather, so there is no interannual SST variation. |
| Sea and lake fish: eat 3×/yr of their mass in zoo; grow 2×/yr (sea) or 0.6×/yr scaled by fed; logistic toward MAX_FISH·phyto | W:252-261 | arbitrary | A 67 % gross conversion is impossible (real 10-25 %). Hunger and a hand-set K act together as double regulation. |
| Shellfish: logistic 0.8/yr toward 20000·phyto/(phyto+3); no food, no deaths | W:263 | arbitrary | Creates mass. They do not filter phytoplankton and they never die except when people take them. |
| River fish: logistic on riverCapacity (width × length × warmth); a dry bed keeps 30 %; no mortality | W:192-198, W:267-273 | rough/arbitrary | The 15 g/m² density is fine. They have no food (aquatic insects from litter) and no deaths. The dry-bed kill and overshoot vanish without being counted. |
| Recolonisation from downstream, 10 % of the density difference per week, blocked at a cascade | W:280-286 | arbitrary | It **creates** fish (nothing is taken from below) and compares per-km²-land with per-km²-water densities. |
| Spawning run: run = 2·max offshore fish·flow factor·exp(-upKm/150), rebuilt each week of a fixed day-120..180 window | W:364-376 | arbitrary | Creates mass from coastal fish without removing them. It is rebuilt weekly, so people's catch from it is free. It never spawns, so it recruits nothing. |
| Cascade = hex-mean gradient > 1.2 % | W:163 | rough | Real barriers are local falls. It is a reasonable proxy at 10 km. |
| Plankton and fish drift 10 %/week toward the neighbour mean | W:277, 293-301 | arbitrary | It is not area-weighted, so it does not conserve mass. Lake and sea hexes mix with each other. |
| Sea mammals: daily ration, births ∝ young·fed, background and starvation mortality, crowding disease ∝ (m/(8·start))² | W:311-340 | rough/arbitrary | Rations and fecundity are plausible. Newborns are counted at **adult** mass, and the disease term is a hand logistic cap. Intake is not converted to growth. |
| Hunters' catch share SEA_CATCH·N/(N+1000) per kind per week | W:308 | arbitrary | The functional response ignores predator numbers. Every kind independently gets up to 6 %/week. |
| Sharks: 70 % fish, then marine mammals by defence, shelter and shark density | W:320-331 | rough | Reasonable structure. |
| Sea-animal monthly spread, toward food and away from crowding, area-weighted | W:343-361 | grounded | Mass conserving. The `/50` is a magic number. |
| Sea-bird colonies: fixed sites at init, 0.25 kg/day, catch 1 %/week, breed 0.4, die 0.1 + 0.5·hunger, yearly resettling | W:379-402 | rough | Plausible. The birds are counted as heads, not kg, and deaths and guano go nowhere. |
| Crocodiles: binary presence where coldest month > 12 °C and river or lake; risk 0.1/yr of young when > 16 °C | W:201-212 | arbitrary | There is no population and no intake. Kills become carrion (HexFauna.kt:367). |
| People fishing: fixed weekly shares 0.3 run / 0.05 river / 0.01 fish / 0.02 shellfish × skill | W:424-439 | arbitrary | Quotas "so a band cannot empty a river". Effort, gear and fish density do not shape the catch. |

---

## 2. Constants

Verdicts: G = grounded, R = rough, A = arbitrary.

### HexSmallLife.kt

| Constant | Line | Value | Meaning | Verdict | Defensible value / form, basis |
|---|---|---|---|---|---|
| litter init | 58 | 0.5·(herb+0.2·shrub+0.04·tree)+0.3 | starting litter kg/m² | R | Init only. Better to spin up from the litterfall/k equilibrium. |
| OM init | 59 | 4·min(1,biomass/5) | kg/m² | R | Topsoil OM 2-10 kg/m² (from memory). Better to spin up to 6·litter input/yr. |
| insects/rodents/herps/pollinators init | 65-69 | 300/150/20/10 ·min(1,herb/0.4) | kg/km² | R | Init only. |
| pollination init | 71 | 0.6 | | R | Init only. |
| eagle crag cap | 81 | min(1, 2·ruggedness) | nest sites | R | Nest-site limitation is real. The ×2 is hand-set. |
| init fraction of K | 109-110 | 0.5 | | R | Init only. |
| fox init | 112 | 0.3 kg/km² | | A | Red fox 0.2-2/km² × 6 kg = 1-12 kg/km² (from memory). |
| vulture cliff ruggedness | 118 | > 0.5 | | R | |
| vulture colony start | 128 | 40 birds | | R | Griffon colonies hold 10-100 pairs (from memory). |
| open() | 132 | 0.5 shrub weight, 1.5 kg/m² | openness | A | Should use shrub cover, not biomass. |
| hare K | 133 | 40·herb/(herb+0.3)·open | kg/km² | A | Cape hare 5-20/km² × 2.5 kg = 12-50 kg/km² (from memory). Should be replaced by intake-based dynamics. |
| hyrax K | 134 | 80·rugged^1.5·min(1,shrub/0.5+0.2) | | A | Local colonies reach 10-50 ind/ha in boulder fields, but landscape means are far lower. |
| grass half-sat | 136 | 0.2 | | A | |
| dry scale | 139 | 500 mm | | R | Sandgrouse are desert birds, so the 500 mm scale is fair. |
| chukar K | 141 | 6, 0.5, 0.3/0.7 | | A | Chukar 5-20 birds/km² × 0.5 kg = 2.5-10 (from memory). The order of magnitude is OK. |
| quail K | 142 | 5 | | A | |
| sandgrouse K | 143 | 3 | | A | |
| rock dove K | 144 | 4·rugged^1.5 | | A | |
| mallard K | 145 | 6·waterfowl | | A | |
| goose K | 146 | 10·waterfowl·grass | | A | |
| tortoise warm range | 151 | 12-20 °C mean | | R | *Testudo graeca* range: mean annual temperature of roughly 13-20 °C. |
| tortoise K | 152 | 30·warm·cover/0.8 | kg/km² | A | Densities run 1-20 ind/ha in good scrub (from memory), i.e. 100-2000 kg/km². 30 is likely low. |
| soil wet scale | 173 | 75 mm | | R | Should be a share of field capacity (`SOIL_CAPACITY`), not an absolute. |
| Q10 coefficient, ref, cap | 174 | 0.069, 15 °C, 2.8, 0 °C cut | | G | Q10 = 2. The cap at about 30 °C is fine. |
| litter k | 178 | 1.2/yr | | R | Mediterranean litter k is 0.3-1.0/yr (from memory). 0.7 is safer. |
| humification | 180 | 0.3 | | R | 0.2-0.4 is typical. |
| OM turnover | 180 | 0.05/yr | | R | 0.02-0.05/yr for the active plus slow pool. |
| organic fertility | 181 | 0.3·OM/(OM+4) | | A | Should derive from the mineralisation flux (0.05·OM·N-content). |
| soil fauna factor | 182 | 20 | g/m² per kg/m²/yr | A | Diagnostic only. |
| carrion rate | 186 | 0.07/day·warm, floor 0.1 | | A | Use 1-exp(-k·days) with k = ln2/10 d ≈ 0.07/day at 25 °C, i.e. ref 25 °C, not 15 °C. Field carcass half-lives are 3-15 d in summer and months in winter. |
| carrion to litter | 187 | 0.3 | | R | 0.2-0.4 of the mass remains as hide, bone and residue. The rest should feed insects (blowfly larvae). |
| BONE_SHARE | 429 | 0.2 | | R | Skeleton is 5-15 % of body mass (from memory). 0.1 is better. |
| bone weathering | 191 | years/2 | e-fold about 2 yr | R | Behrensmeyer weathering stages span 5-15 yr in arid land (from memory). |
| food | 192 | herb + 0.3·shrub | | R | |
| dry soil | 196 | < 10 mm | | R | Should be a share of capacity. |
| heat-drought insect kill | 198 | t > 32 °C, 2/yr, cap 0.5 | | R | |
| insect activity | 199 | 10 °C | | G | Typical developmental thresholds are 8-12 °C. |
| insect ceiling | 200 | 2000·food/(food+0.15), floor 10 | kg/km² | A | Remove the hand ceiling and let intake and predation limit them. Grassland arthropods are 1-5 g/m² = 1000-5000 kg/km² fresh (from memory). |
| insect growth | 201 | 15/yr·(t-10)/15 | | R | Multivoltine growth is plausible. |
| insect deaths | 202 | 2/yr | | R | |
| insect ration | 205 | 0.2 herb + 0.05 shrub /day | | R | 10-30 %/day. |
| FRASS | 427 | 0.5 | | R | 0.5-0.7 for insects (low assimilation). Rodents return about 0.3 (higher digestibility). |
| insect cold die-off | 207 | 6/yr, cap 0.9 | | R | |
| insect clamp | 209 | 3000 | | A | Remove. |
| biting thresholds | 211 | wetland > 0.1, soil > 0.9 of capacity | | R | |
| biting rule | 212 | 15 °C, +60/day·max(w,0.3), cap 500, ×0.7 | | A | Needs a stock with breeding ∝ water and warmth × blood meals, and mortality per day. |
| rodent breeding | 215 | > 5 °C, 5/yr, half-sat 0.2 | | R | Voles and gerbils reach r_max 3-8/yr (from memory). |
| rodent deaths | 216 | 1.2/yr; +3/yr if snow > 30 mm or t < -5 °C | | R | |
| rodent ceiling | 217 | 600·food/(food+0.3) | | A | Peaks of 100-300 voles/ha × 30 g = 3000-9000 kg/km². The ceiling should come from intake. |
| rodent clamp | 220 | 3000 | | A | Remove. |
| rodent ration | 221 | 0.1/day | | G | |
| herp moisture | 225 | 40 mm, floor 0.2 | | R | |
| herp growth | 226 | 4/yr, half-sat 400 | | R | 1-2/yr for lizards and higher for amphibians (from memory). Should be tied to intake. |
| herp ceiling | 227 | 80·food/(food+0.3) | | A | Lizards 10-100 kg/km² is OK in magnitude. Should come from intake. |
| herp deaths | 228 | 1/yr | | R | |
| herp ration | 230 | 0.05/day | | R | 0.01-0.03/day is realistic. |
| herp torpor deaths | 231 | 0.3/yr | | R | |
| herp clamp | 232 | 200 | | A | Remove. |
| fire kills | 236, 309 | 0.8/0.7/0.5/0.5; 0.6/0.3/0.3 | | R | |
| flowering months | 240 | 3..7 | | A | Should be a plant-phenology signal (warm and moist growing weeks). Levant flowering is roughly Feb-May. |
| flowers | 241 | 0.5 shrub, 0.2 off-season, 0.5·(1-exp(-grazing/5)) | | A | |
| pollinator activity | 243 | 12 °C | | G | Bees forage above about 10-12 °C. |
| pollinator K | 244 | 50·flowers/(flowers+0.3), floor 0.5 | | A | |
| pollinator growth/deaths | 246-247 | 10/yr, 1/yr, cold 2/yr cap 0.5 | | R | |
| pollinator clamp | 248 | 100 | | A | |
| pollination smoothing, saturation | 249 | 0.2, 20 kg/km² | | A | |
| fruit set floor | 392 | 0.3 + 0.7·p | | R | Wind and self-pollination floor of 0.2-0.4. |
| spread period, rate | 255, 400 | every 5 steps, 0.2 | | A | Should be per taxon, from dispersal km/yr (diffusion D = L²/4t). Must be area-weighted. |
| mild | 261 | 5 °C | | R | |
| hare r, d, months | 262 | 4/yr, 0.9/yr, months 3..9 | | R | Hare annual r_max ≈ 1-2. Adult survival 0.4-0.6 suits a death rate of 0.9. |
| hyrax r, d, months | 263 | 2.5, 0.4, 4..6 | | R | 2-3 young per year. |
| tortoise activity | 277 | 14..33 °C, soil > 8 mm | | R | |
| tortoise r, d | 278 | 0.8 (months 4..8), 0.15/0.05 | | R | Adult survival is about 0.95, so a death rate of about 0.05 is right. r = 0.8 is far too high for a species maturing at about 10 yr; r_max ≈ 0.1-0.2. |
| fox insect share | 281 | 0.03 | | A | Insects are counted but not removed. |
| fox ration | 282 | 0.15/day | | A | 0.06-0.08 for a 5-6 kg fox (300-450 g/day, from memory). |
| fox prey cap | 283 | 0.1 of prey per week | | A | Replace with a Holling type II per predator. |
| fox breeding | 289 | months 4..6, 1.5·fed | | R | 4-6 cubs a year. |
| fox deaths | 289 | 0.4 + 0.8·(1-fed) | | R | |
| fox K | 289 | 0.02·prey | | A | Remove. Hunger and mortality already regulate them. |
| eagle prey visibility | 293 | 0.3 + 0.7·open | | R | |
| EAGLE_NEED | 422 | 0.01 kg/km²/day at index 1 | | R | A pair eats about 0.5 kg/day over 50-150 km², i.e. 0.004-0.01. |
| eagle prey cap | 295 | 0.1/week | | A | |
| eagle fawn subsidy | 304 | +0.3 fed | | A | Credit the actual eagleRisk kills instead. |
| eagle breeding/deaths | 305 | 0.6 in months 4..6; 0.1 + 0.6·(1-fed) | | R | |
| EAGLE_KM2 | 424 | 100 km²/pair | | G | Mediterranean territories are 50-150 km² (from memory). |
| EAGLE_RATE | 425 | 0.15/yr of young | | A | Should be derived from eagle need × the share of young in the diet. |
| logistic overshoot | 329 | deaths ×2 above k, k ≥ 0.01 | | A | |
| vulture ration | 341 | 0.5 kg/day | | G | Griffons average 0.4-0.6 kg/day. |
| vulture breeding | 349 | months 1..2, 0.5·fed·years·6 | about 0.5 young/bird/yr gross | R | One egg a pair with about 60-70 % success, so about 0.35 young/bird/yr net. |
| vulture deaths | 350 | 0.08 + 0.4·(1-fed) | | G/R | Adult survival is 0.9-0.95. |
| vulture floor and cap | 351 | < 2 → 0; 2000 | | A | Allow refounding from colonies within range. The cap should come from nest ledges or food. |
| herps active | 356 | 12..34 °C; not when > 28 °C and < 10 mm | | R | |
| catchSmall cap | 369 | 0.5 of all per call | | A | Use a per-predator functional response. |
| VULTURE_RANGE_KM | 419 | 80 | | G | Griffons forage 50-150 km from the colony. |
| VULTURE_SPACING_KM | 420 | 80 | one colony per block | A | Should come from cliffs and food, not a grid. |
| MIGRANT_SURVIVAL | 430 | 0.7 | | R | This is survival only. Recruitment while away is missing, and the return should be survival + productivity (about 0.9-1.3× of departures). |
| DUNG | 432 | 0.5 | | R | Large herbivores return 0.4-0.6 of intake (dry). Carnivores return about 0.1-0.2, but HexFauna.kt:287 and HexMeetings.kt:196 use 0.5 for meat. |
| bird growth/deaths | 411-416 | r 3-6/yr, d 0.5-1.2/yr; breeding months | | R | Galliform r_max is 2-4, so 6 for quail is OK. Greylag breedMonths 5..6 do not overlap its present months (bug). |
| domestication arrays | 411-416 | 0.2-0.9 | | A | Out of scope here. Hand scores. |

### HexWater.kt

| Constant | Line | Value | Meaning | Verdict | Defensible value / form, basis |
|---|---|---|---|---|---|
| shellfish zone | 88, 97 | ≤ 3 km from land, < 10 m deep | | R | |
| mouth flow split | 122 | /6 to each neighbour | | R | |
| lake supply | 127 | closed 0.2, open 0.6; closed if > 0.5 of cells | | A | Closed lakes should depend on salinity, and above about 50 g/l they hold no fish (Lisan, Dead Sea). |
| sea supply | 128 | 0.15 + 0.45·exp(-depth/150) + 0.4·min(1,flow/50) | | A | Should be a deep-nutrient pool plus river export from current flow × land OM/erosion. |
| plankton init | 129 | phyto 2·supply, zoo 0.5·supply | | R | Init only. |
| river fish init | 167 | 0.7·capacity in month 6 | | R | |
| colony ruggedness | 173 | ≥ 0.3 | | R | |
| colony birds init | 183 | 2000 | | R | |
| fish K half-sat | 188 | phyto 5 g/m² | | A | Remove the hand K and let zoo limit fish. |
| shellfish K half-sat | 189 | phyto 3 | | A | |
| river warmth | 195 | (T+5)/25, 0.2..1.2 | | R | |
| river min flow | 193 | 0.01 | | R | Numeric. |
| croc water | 203 | lake share > 0.02 | | R | |
| croc activity | 210 | water > 16 °C | | R | Nile crocodiles feed little below about 18-20 °C. |
| latitude | 222 | 38° | | R | Should be the hex latitude (about 31-34°N). |
| mixing | 234 | 14/20 °C; 0.25/0.08/0.02 per day | | A | Make it continuous: mixing ∝ wind² × (1 - stratification(T)). |
| recycling share | 236, 244 | 0.5 | | R | 0.5-0.9 of production is recycled in the euphotic zone. The rest must go to a deep pool, not vanish. |
| phyto warmth | 239 | (T+2)/22, 0.1..1.2 | | R | Eppley: 0.59·exp(0.0633·T)/day. |
| phyto µmax, half-sat | 241 | 0.9/day, 0.15·scale | | G | µmax 1-2/day. |
| zoo grazing, half-sat | 242 | 0.6/day, 3 g/m² | | R | |
| plankton mortality | 243 | 0.05, 0.05 + 0.15·z² | | R | Standard NPZ closure. |
| zoo assimilation | 246 | 0.3 | | G | Gross growth efficiency 0.25-0.35. |
| fish ration | 254 | 3·mass/yr | | A | Q/B for small pelagics is about 8-15/yr (Ecopath, from memory). |
| fish zoo share | 255 | 0.5 of zoo per week | | A | |
| fish starvation deaths | 258 | 0.5·(1-fed)/yr | | R | |
| shellfish growth | 263 | 0.8/yr | | R | There is no mortality term. |
| dry river | 271 | flow/mean < 0.05 → ×0.3 per step | | A | Should be applied per day and the dead routed to carrion or litter. |
| upstream recolonisation | 285 | 0.1 of difference | | A | Should be a flux taken from below. |
| whale zoo share | 318 | 0.2 per week | | A | |
| shark fish share | 322 | 0.7 | | R | |
| seal/porpoise shelter | 326 | 0.5 | | A | |
| sex ratio | 334 | 0.5 | | G | |
| sea mortality | 336 | cap 0.9, 0.6·(1-fed) | | R | |
| spread share | 347 | dailyKm·30/hexKm/50, cap 0.5 | | A | The `/50` is magic. Use diffusion from dailyKm and residence. |
| spread food attraction | 354 | +0.01, +1 | | R | Numeric guards. |
| run decay outside window | 366 | ×0.3 | | A | |
| run flow threshold | 373 | flow/mean < 0.2 | | R | |
| run size | 374 | 30 m³/s, 150 km e-fold | | A | |
| sea-bird deaths | 392 | 0.1 + 0.5·(1-fed) | | R | Adult survival 0.85-0.95. |
| colony extinction | 393 | < 2 | | R | |
| resettling | 398-399 | source > 500, 5 %/yr | | A | |
| people's shares | 432-433 | run 0.3, river 0.05 per week | | A | |
| fin whale | 465 | 50 t, 1.5 %/day, 100 km/day, birth month 0 over 2 months, 0.4 young, 0.03 mortality, start 1/800 km² | | G/R | Calving interval 2-3 yr. Newborns counted at adult mass (bug). |
| dolphin | 466 | 80 kg, 5 %/day, 0.4, 0.06 | | R | |
| porpoise | 467 | 55 kg, 8 %/day, 0.8, 0.12, ≤ 25 km from shore | | R | Harbour porpoise 7-10 %/day. |
| monk seal | 468 | 300 kg, 5 %/day, 0.5, 0.07, ≤ 12 km | | R | |
| shark | 469 | 800 kg, 1 %/day, 0.6, 0.05 | | R | |
| NUTRIENT_SCALE | 472 | 10 g/m² | | R | |
| MAX_FISH | 474 | 6000 kg/km² | | A | 6 g/m² is a plausible shelf fish biomass, but it should be emergent, not a cap. |
| RIVER_FISH_PER_KM2_WATER | 475 | 15000 | | G | 5-30 g/m² in warm rivers. |
| MAX_SHELLFISH | 476 | 20000 | | R | |
| FISH_GROWTH | 477 | 0.6/yr | | R | P/B for lake fish is 0.3-1. |
| FISH_START | 479 | 0.2 | | R | Init. |
| DRIFT | 480 | 0.1/week | | A | Should be a current exchange, area-weighted, sea↔sea only. |
| SEA_FISH_GROWTH | 481 | 2/yr | | R/A | P/B of 1-2 is fine, but with Q/B = 3 the efficiency is 0.67. |
| FISH_DEATHS | 483 | 0.3/yr | | R | |
| CASCADE_GRADIENT | 485 | 0.012 | | R | |
| RUN_START_DAY / RUN_DAYS | 486-487 | 120 / 60 | | R | Shad run roughly Apr-Jun. It should be triggered by a river-temperature threshold. |
| RUN_KM_PER_DAY | 488 | 20 | | G | Shad migrate 10-30 km/day. |
| RUN_PER_COASTAL_FISH | 489 | 2 | | A | The run must come out of a stock. |
| UPWELL_SUPPLY / WIND / WEEKS | 491-493 | 0.6, 8 m/s, 3 | | R | |
| SEA_FULL / SEA_DISEASE | 495, 503 | 8×start, 0.5 | | A | Hand density cap. |
| SEA_CATCH | 497 | 0.06/week | | A | Should be per predator. |
| PEOPLE_FISHING / SHELLFISH | 499-500 | 0.01 / 0.02 per week | | A | Quotas. |
| FISH_HALF | 502 | 1000 kg/km² | | R | |
| SHARK_KG_KM2 | 504 | 0.5 | | A | |
| SHARK_ATTACK | 506 | 0.1/yr | | A | |
| BIRD_FORAGE_KM | 508 | 40 | | R | Cormorants and terns 10-40 km; shearwaters far more. |
| BIRD_KG_DAY | 509 | 0.25 | | R | |
| BIRD_CATCH | 511 | 0.01/week | | A | |
| RESETTLE_KM | 512 | 200 | | R | |
| COLONY_SPACING_KM | 513 | 25 | | A | Grid placement. |
| BIRD_BREED | 514 | 0.4/yr | | R | |
| CROC_COLDEST | 516 | 12 °C | | R | Nile crocodiles need water of about 15 °C or more year round, so the Levant (Nahal Taninim) is marginal. |
| CROC_RATE | 517 | 0.1/yr of young | | A | No population behind it. |

---

## 3. Matter budget (closed-system leaks)

**Created from nothing:**
- Hares, hyrax, all six birds and tortoises grow through `logistic()` with no intake (S:262-278).
- Pollinators eat nothing (S:246).
- Herps grow on insect *density*; the insects they eat are destroyed without becoming herp mass (S:226-230).
- Sea fish convert at 67 % (W:254-259). Shellfish have no intake (W:263). River fish grow with no food (W:270). Upstream recolonisation adds fish without taking any from below (W:285).
- Spawning run fish are generated from the sea stock without removing it, every week of the window (W:374).
- Sea-mammal births add adult-mass newborns proportional to `fed`, not to intake (W:334).
- Migrant returns (S:165) are an import, which the owner allows, but there is no breeding term.

**Destroyed without accounting:**
- 70 % of rotting carrion (S:187), with no insect or maggot uptake.
- 70 % of decomposed litter and 5 %/yr of OM (S:180) go to CO2. That is physically fine for carbon, but N and P vanish too.
- Logistic overshoot above K is removed but never added to `smallDead`: insects S:203, rodents S:218, herps S:229, pollinators S:246, `logistic()` S:331, sea fish W:259, shellfish W:263, river fish W:270.
- Prey eaten by foxes (S:286), eagles (S:299-301), herps (S:230), vultures (S:345), sea mammals (W:318-328), sea birds (W:386) and fish (W:256) produces no faeces or excreta. Only the large fauna and people return dung (HexFauna.kt:287, 312; HexMeetings.kt:196).
- Eagle deaths are uncounted (S:305). Vulture and sea-bird deaths are counted as heads and go nowhere (S:350, W:392).
- Dry-river fish kill (W:271) and run decay (W:366).
- 50 % of plankton waste "sinks" each day (W:244), and dead sea animals and fish only half-recycle (W:236).
- Fish taken by people are not logged in `fishFlows`. Shellfish shells (middens) are not returned to land.

**Non-conservative transport:**
- `spread()` (S:394-402) and `drift()` (W:293-301) average densities over unequal areas. Drift also mixes lake water with the sea; W:278 already measures this as "drift (net)".
- Eagles leak into crag-less places and freeze there.

**External inputs not declared as such:**
- Nutrient mixing toward a fixed `supply` (W:235). The deep sea acts as an infinite source.
- River nutrient supply is static mean flow (W:119-128). It is not linked to soil OM, fire ash or erosion.
- Biting insects appear from nothing (S:212). They are not counted in any mass.

---

## 4. Interactions

### Present
- Plants → insects and rodents (consume, with frass to litter).
- Litterfall, dung and small dead → litter → OM → `organicFertility` → plant growth (HexLand.kt:242).
- Insects → herps. Rodents, hares and birds → foxes. Hyrax, hares and rock birds → eagles. Small prey → omnivores and bands (`catchSmall`).
- Carcasses → carrion → vultures and mammal scavengers. Bones → bone-crackers.
- Grazing → fewer flowers → pollinators → fruit set (HexFood.kt:40).
- Fire kills small life. Snow and cold kill rodents. Wetland → biting insects (no downstream effect).
- Eagle and crocodile risk → young of herds (HexFauna.kt:352-354; HexLifeAndDeath.kt:224-228).
- Nutrients → phyto → zoo → fish and whales. Fish → dolphins, porpoises, seals, sharks, sea birds and people. Sharks → marine mammals.
- Wind → upwelling. River mouth → nutrients. Sea fish → spawning run → people.

### Missing (with why it matters)
1. **Small herbivores do not eat plants.** Hares, hyrax, birds and tortoises do not compete with the herds, and their densities do not respond to overgrazing or drought. In real systems a rodent or hare boom suppresses regrowth, and heavy grazing removes hares.
2. **Granivory and frugivory.** Rodents, birds and ants eat seeds and fruit (`HexFood.fruit`), which limits recruitment and links to the mast years that drive rodent outbreaks.
3. **Insectivory by birds and bats.** Quail and chukar chicks, and most passerines, live on insects. The breeding success of the birds and the summer insect peak should be coupled.
4. **Raptors and owls on rodents; snakes on rodents.** Rodents are regulated only by food, a hand ceiling and foxes. Real rodent cycles are driven by predation (owls, kestrels, snakes, mustelids).
5. **Carrion → blowflies and beetles → birds.** Decomposer insects carry most carcass mass in summer. That is the real reason vultures race against rot.
6. **Dung beetles and termites.** They bury dung into soil OM, which matters for the nutrient return under herds in dry country.
7. **Biting insects → herds and people.** Fly harassment drives herds uphill or to wind and costs grazing time; vectors spread disease (malaria for people, trypanosomes and bluetongue). Today the field is decorative.
8. **Rodent and small-mammal disease** (plague, tularemia). These produce boom-crash cycles and are the zoonosis link to people.
9. **Pollinator phenology from the plants.** Flowering is a fixed calendar, so a dry spring does not cut the fruit crop through pollination.
10. **Land ↔ sea nutrient links.** River export of soil OM, ash and silt; sea-bird guano onto coasts; beach-cast and stranded whales and seals as carrion for vultures, hyenas and people; spawning-run marine nutrients carried upriver (dead shad).
11. **River food web.** River fish should eat aquatic insects fed by litter and riparian inputs. Herons, otters, bears, ospreys and kingfishers take river fish.
12. **Lake salinity.** Closed hypersaline lakes have no fish, but the model gives them some.
13. **Weather → water temperature.** Uses normals only, so warm years do not stratify or starve the sea.
14. **Crocodiles as a population** that eats fish and drinking animals and leaves kills, not carcasses for others.
15. **Foxes and jackals on carrion and fruit.** These are large real shares of their diet. The golden jackal, the main Levant mesocarnivore, is absent.
16. **Litter as fire fuel** (check HexLand). Here burning does not reduce litter or OM, and ash does not return nutrients.

---

## 5. Top 10 problems (ranked by distortion)

1. **Carrion rots before anyone eats it, and too fast.**
   - Problem: carcasses are added and rotted at S:184-189 before `stepVultures` (S:254), and mammal scavengers only reach the leftover next week. At 25 °C, 84 % rots per week; at ≥ 28 °C, 100 % (`min(1, 0.07·warm·6)`), against the documented "half in ten days". Summer carrion is lost almost entirely, and vultures and hyenas starve.
   - Fix (reshape, no new mechanism): run `stepVultures` and the scavenger pass on fresh carrion before rotting. Use `gone = carrion·(1-exp(-k·days))` with k = 0.07/day at 25 °C (Q10 = 2) and a cold floor.
   - Fix (NEW link): route a share of the rotted 70 % into `insects` (blowfly larvae).
2. **Migrant birds go extinct by construction.**
   - Problem: the greylag goose's breedMonths 5..6 never fall in its present months {10..3} (S:416), so it never grows. All migrants lose 30 % per absence (MIGRANT_SURVIVAL, S:430) with no recruitment while away.
   - Fix (tuning): make the return factor survival × (1 + productivity·fed-before-departure), about 0.7 × 1.4 for geese and much higher for quail. Or give geese a growth term applied to `away` (breeding off-map, which the owner allows as an input). Quail stay as is: they breed here.
3. **Small game and shellfish create mass and never compete for forage.**
   - Problem: hares, hyrax, six birds, tortoises (S:262-278) and shellfish (W:263) grow by hand logistic toward hand K with no intake.
   - Fix (reshape): give each a ration (hares 0.3 kg dry/day for 3 kg; hyrax 0.1/day; galliforms about 0.07/day) taken through `land.consume` or `HexFood`. Growth = efficiency × intake − maintenance, so K emerges. Shellfish filter `phyto`. Same pattern as rodents and insects (S:205, S:221).
4. **Fish trophic efficiency is 67 % plus a hand K.**
   - Problem: fish eat 3×/yr and grow 2×/yr (W:254, 481); MAX_FISH (W:188, 474) caps on top. The coastal fish base for seals, dolphins, sea birds, people and the run is therefore unconstrained by energy.
   - Fix (tuning): set Q/B about 10/yr. Growth = 0.2·intake − 0.3/yr maintenance. Drop the `(1 - f0/K)` term and let zoo limit fish. Send the unassimilated 80 % to `nu` as excretion.
5. **The spawning run is a free, infinite fish source.**
   - Problem: it is rebuilt every week from 2× the offshore density without removing sea fish (W:369-374), so people's 30 %/week catch (W:432) never depletes it, and it recruits nothing.
   - Fix (reshape): make `run` a stock moved from coastal `fish` at the window start. Advance it upstream, deplete it by catch, then return the survivors to the sea fish (with recruitment) and leave the post-spawn dead as carrion or nutrients on land.
6. **Transport does not conserve mass.**
   - Problem: `spread()` (S:394-402) and `drift()` (W:293-301) average densities across unequal areas, and lake and sea water mix. Eagles leak into crag-less places and freeze there (S:291 gate). River recolonisation creates fish (W:285).
   - Fix (reshape): exchange fluxes, with Δmass = D·(ρq−ρp)·min(area) moved from one place to the other. Restrict drift to the same water kind. Set D per taxon from dispersal (tortoises far below birds). Make river recolonisation a transfer from below.
7. **Fixed weekly quotas and per-kind catch shares replace functional responses.**
   - Problem: catchSmall's 50 % per call (S:369); fox 10 % (S:283); eagle 10 % (S:295); SEA_CATCH 6 % per kind regardless of predator numbers (W:308); people's 1 %/2 %/5 %/30 % (W:432-436).
   - Fix (reshape): use one Holling type II, `take = a·N·P·days / (1 + a·h·N)`, per predator biomass P, with search rate `a` and handling `h` per kind. That gives saturation and the low-density refuge without quotas, and people's catch then depends on band size and gear (skill → a).
8. **Hand ceilings, clamps and uncounted overshoot.**
   - Problem: insect 2000·food and 3000; rodent 600·food and 3000; herps 80·food and 200; pollinators 50·flowers and 100; fox K = 0.02·prey; vulture cap 2000; SEA_FULL/SEA_DISEASE; `logistic()` ×2 deaths. Overshoot mass vanishes.
   - Fix (reshape): once intake (#3) and functional responses (#7) exist, drop K terms and clamps. Keep density-dependent mortality only where there is a mechanism (disease, territoriality) and route all deaths to `smallDead`/`dead`. In the meantime, at minimum, add `max(0, -growthTerm)` to `smallDead`.
9. **Predator energetics have phantom food and double counting.**
   - Problem: the fox ration of 0.15/day is about 2× real, and insects count as prey but are never taken (S:281-286). The eagle "+0.3 fawns" (S:304) is phantom food, while eagle-killed fawns go to carrion (HexFauna.kt:352, 367). Crocodile kills also become carrion and crocodiles are not a population (W:201-212).
   - Fix (tuning and reshape): set the fox ration to 0.07 and take insects or drop them. Credit `eagleRisk` kills (kg) to eagle `got`, remove them from `carcassKg`, and drop the +0.3. Do the same for crocodiles: a minimal biomass field fed on drinking young and fish (NEW, small).
10. **The sea's nutrient budget is open, and Lisan grows fish.**
    - Problem: 50 % of waste sinks out daily (W:244); mixing pulls toward a fixed `supply` (W:235); the river input is a static mean flow (W:119-128); closed lakes get supply 0.2 (W:127). There is no excretion by fish or mammals, and no guano back to land.
    - Fix (reshape): add a deep-nutrient pool per sea hex that receives the sinking flux and feeds mixing and upwelling. Make river supply the current `land.flow` × land export (OM, ash). Give closed lakes salinity from the evaporation balance, with fish growth 0 above about 50 g/l.
    - NEW, smaller links: colony guano → coastal place `litter`; stranded marine-mammal deaths → coastal `carrion`.

**Also notable:**
- Biting insects are decorative (S:212); either wire them to herd harassment and disease (NEW) or drop them.
- Pollination runs on a fixed calendar (S:240).
- Water temperature uses normals, not the weather (W:233).
- Light uses a fixed 38° latitude (W:222).
- DUNG = 0.5 is applied to meat (HexFauna.kt:287; HexMeetings.kt:196), but carnivores egest only about 0.1-0.2.
- The tortoise r of 0.8/yr (S:278) is about 5× a long-lived chelonian's.
