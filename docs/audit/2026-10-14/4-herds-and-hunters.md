# Audit 4: groups (herds, packs): moving, hunting, meetings, life and death, bearings

Scope: `HexGroups.kt`, `HexGroup.kt`, `HexMeetings.kt`, `HexLifeAndDeath.kt`, `HexGroupRules.kt`, `HexBearings.kt` (all under `src/main/kotlin/inflation/ecology/`). People-only code is skipped except where it changes animal rules. "From memory" means a literature value recalled, not looked up.

Worked numbers used below (from `Catalogue.kt` and `Allometry.dailyIntakeKg = 0.2 M^0.75` for carnivores):

| kind | pack (groupSize x kg) | strength (adults x kg x ferocity) | swept km²/week (`sweptKm2`) | meat need/week, start group | diet meat / small / carrion / fruit |
|---|---|---|---|---|---|
| lion | 5 x 150 = 750 | 900 | 12·6·2·8·0.3 = **346** | ≈255 kg | 0.9 / 0 / 0.1 / 0 |
| leopard | 2 x 45 = 90 | 99 | 6·6·2·4·0.3 = **86** | ≈25 kg | 0.6 / 0.35 / 0.05 / 0 |
| cheetah | 2 x 45 = 90 | 54 | **173** | ≈34 kg | 0.8 / 0.2 / 0 / 0 |
| jackal | 2 x 10 = 20 | 16 | **115** | ≈1.3 kg | 0.1 / 0.5 / 0.25 / 0.15 |
| hyena | 2 x 35 = 70 | 98 | **173** | ≈3 kg | 0.1 / 0.25 / 0.55 / 0.1 |
| wolf | 3 x 22 = 66 | 66 | **432** | ≈23 kg | 0.6 / 0.25 / 0.15 / 0 |

A hex is about 87 km².

---

## 1. Rules

### Planning and moving (HexGroups.kt)
- **Start placement** (HexGroups.kt:96-121): groups are put down by area × suits × climate × food, at the settled density. *Rough*: fine for initialising. The default of 50 km²/animal (:97) and the start young of `groupSize/3` (:106) are arbitrary.
- **Restlessness** (:274-278): a carnivore is restless when fed < 0.5, homeless, thirsty or astray. A herd is restless when fed < 0.7, its discomfort is > 0.5, it is snowed in, thirsty or astray. *Rough*: these are hard thresholds, and the two classes get different numbers for the same idea.
- **Content groups skip planning every other week** (:281). *Arbitrary*: this is a compute shortcut that halves how often fed herds can respond to the season.
- **Reach = travel × SHIFT_DAYS** (:282): 2 days of going, or the forced-march speed when restless. *Rough*: a herd that is fed but on its seasonal trek gets only its slow reach (the trek does not make it restless). The doc comment at :194 says "heading for its season's place" gives a forced march, but the code does not do that.
- **Dijkstra over crossings with a snow cost** (:292-306). *Grounded* in form. The snow cost is the same for every species; it should scale with chest height or `snowLimitMm`.
- **Territory confinement** (:320): a pack with ground stays within `territoryKm` while fed ≥ 0.5. *Arbitrary*: the radius is fixed by `startKm2PerAnimal` (:412) and does not depend on prey. The `Species.territoryKm` field is ignored here.
- **Foreign marks exclude** (:321): skip any hex with a foreign same-kind mark > 0.3 while fed ≥ 0.3. *Grounded* in idea. The 0.3 fed threshold is arbitrary.
- **Hunter place score** (:328-333): prey/(prey+PREY_HALF) − 0.5·crowd − danger. *Arbitrary*: `placeValue` is big game only (`preyHere + herdPrey`, :223), and PREY_HALF is the same for a 10 kg jackal and a lion pride. Jackals (90% non-big-game diet), hyenas and leopards choose ground by food they barely eat.
- **Danger from stronger hunters** (:332): a flat −0.3 when the single strongest party last week is more than 2× stronger. *Arbitrary*: it is a step function, has no memory, and ignores every party but the top one.
- **Herd place score** (:335-340): food/(food+0.3) − 0.5·crowd − SHUN·hunterKg term. *Rough*: hunters are counted by kg, whatever they hunt, so a jackal pair "threatens" an aurochs herd.
- **Weather, forecast, water, snow, distance terms** (:342-353). *Rough*: these are hand utility weights (0.5, 0.3, 0.02, 0.3, −1, −1, 0.1) with no common currency.
- **Season pull** (:355-360): SEASON_PULL·(0.3·Q + max(0, Q − fed))·progress. *Arbitrary*: progress is measured against the whole distance to the goal (up to 120 km), not against the reach. Worked example: a fed herd with a 30 km reach and a 120 km goal gets ≤ 0.3·1·0.25 = 0.075, which is less than STAY = 0.15. A fed herd never sets off.
- **Bearing pull** (:361; HexBearings): the mean way to better places now, weighted by gain and nearness. *Rough*: it points to better places this month (it sees only month m and m+1 through `monthFit`), not to where the herd should be next month.
- **Hungry pack leaves hunted-out ground** (:368): −0.5 on the current and recent hexes when fed < 0.5. *Rough*: a proxy for prey depression, which is not modelled.
- **Noise 0.02** (:398) to break ties. Fine.
- **Crowding** (:418-422): others/(others + 3·usual + 1). *Rough*.
- **Monthly huntable / herd game at HERD_GAME** (:146). *Rough*.
- **Bearings quality for hunters** (:45-51): uses big-game `huntable` only, with a water term that switches at waterFromFood ≥ 0.8 (a special case). *Arbitrary* for the same reason as the place score.

### Hunting (HexMeetings.kt)
- **catchable** (HexMeetings.kt:27-40). Multiplies size, small, defence, refuge, hide, dark and speed.
  - `size` uses the species' `groupSize·massKg`, not the group's actual adults (:28). *Arbitrary*: a lone lion is credited with a pride.
  - `small` is a ×0.3 step below 5% of hunter mass (:30). *Rough*.
  - `hide` = 1 − 0.8·cover for prey < 70 kg, for every hunter (:34). *Arbitrary and inverted for stalkers*: leopards hunt by cover, yet cover takes 80% off their main prey and nothing off lions' big prey.
  - `dark` (:36): *grounded*; moonlight affects lion success (from memory: Packer et al. 2011).
  - `speed` (:38): *grounded* in form.
- **Resident hunt = density × swept × CATCH × catchable × awake, Poisson, capped at want+1** (:59-87). *Rough*: this is a Type I response cut off by a hard quota, with no handling time and no prey refuge at low density.
  - Prey kinds are hunted in **catalogue order** (:64) until the quota is met. This is an *arbitrary* prey choice, not profitability.
  - Kills are capped at 50% of residents per week (:74). *Arbitrary*.
- **Sweep share** (:58): `swept/path.size·min(2, path.size)`. *Arbitrary*: moving doubles the total ground swept. When staying, the sweep is capped at hex area (:63), which cuts a lion's 346 km² to 87 and makes it equal to a leopard's.
- **Hunt stops at `need = need·diet.meat`** (:50, :60, :65). *Arbitrary quota*: this is what defeats meat-side diet switching (see section 4, #1).
- **Small game & carrion** (:89-101): take up to half the standing stock of the swept area. No catchability and no species skill. *Rough*.
  - Accounting bug: `catchSmall` and `scavenge` lower the density over the whole place but credit only the swept km². A moving group therefore removes about area/swept (up to ~3×) more than it eats. This depletes jackals' small prey and carrion.
- **Diet switching** (:128-163): carrion, small prey and fruit can make up a shortfall up to (1+SWITCH)× their share. *Grounded* idea, broken for meat. Meat can only use `got − meatEaten`, which is the overshoot of a hunt that stopped at its quota. Big-prey hunters (lion: one aurochs gives 450 kg) always have a large overshoot. Gazelle killers (12 kg each) have almost none.
- **fed = eaten/wantAll** (:192); leftover meat goes to carrion (:195). *Grounded*. Gorging is not modelled: the surplus cannot become fat.

### Meetings (HexMeetings.kt)
- **Meeting list from timetables, sorted by day, MAX_MEETINGS 4** (:228-278). *Rough*: the cap of 4 also caps predation on migratory herds.
- **meetChance** (:316-324) = min(1, (swept_a + swept_b)·track·span/days/area)·hours. *Rough*: for two carnivores that share a hex all week it is ≈ 1 × hour overlap (lion–leopard 0.9, hyena–jackal 0.7, wolf–jackal 0.9). So every shared week gives one meeting.
- **slipsAway** (:331-341): only the weaker carnivore tries, and only when more than 2× weaker. The chance is about 0.13–0.17 plus shelter, capped at 0.9. *Rough*: the 2× threshold is arbitrary.
- **Hunter meets herd: vigilance turn-aside** (:359). *Grounded* idea. The 0.95 cap and the long-lived wariness are rough.
- **Not worth trying below catchability 0.05** (:372). *Rough*: it should be a profitability comparison.
- **Chase success = CATCH·SURPRISE·catchable·(1 − 0.5·wary)** (:373). *Rough*: CATCH is the same for all species, and SURPRISE always applies, since the "seen" branch returns first, so it is effectively CATCH×1.5.
- **50/50 young or adult kill** (:395). *Arbitrary*: it should follow the young share and their catchability.
- **Fight back** (:414): FIGHT_BACK·defence·min(2, 2·prey/pack). *Grounded*.
- **enemiesMeet theft** (:428-435): if ratio ≥ 1.3, 50% × (1 − canopy if the weak can climb) of the time 70% of `meatMet` is taken. *Rough*: only herd-meeting meat can be stolen, not resident kills. Tree caching is a good, grounded special case.
- **Intraguild kill** (:438-442): ratio > 2 → 10% per meeting, always an adult, (1 − 0.7·shelter). *Arbitrary and far too high*. Example: a leopard pair sharing a hex with a pride for a year has ~52 × 0.9 × 0.85 × 0.1 ≈ 4 adults killed. Real intraguild killing falls mostly on cubs.
- **Weaker turns back one hex** (:443). *Rough*: it does nothing to a group that stayed put (index 0).
- **Marks** (:451-463): keep 0.85 per week, fresh > 0.3 (about 7 weeks). *Grounded*.
- **rivalsMeet** (:470-485): ratio > 1.5 → the weaker gives way; otherwise they fight, with a 5% death and the loser losing its home if the fight is at home. *Rough*. There is no infanticide or takeover.
- **settle** (:488-495): a homeless pack takes the current hex as home if it is unmarked. *Rough*: home becomes a single hex wherever the pack happens to be.

### Life and death (HexLifeAndDeath.kt)
- **inheritSeasons** (HexLifeAndDeath.kt:24-54): the best growing place within 120 km per month, with a hard snow factor of 0.2 for grazers below −1 °C. *Rough*. `r` (:29) is unused.
- **graze** (:60-103), with gut fill and switching up to (1+SWITCH). *Grounded*.
- **Fat** (:172-173): draws below fed 0.9; gains above 0.9, 0.8 days/day in months 7–10, 0.3 otherwise. *Rough*: carnivores get the autumn rate too, and there is a cliff at 0.9.
- **Birth-month drift toward the best 3-month seasonQ window** (:176-181). *Grounded* idea, but seasonQ saturates at fed = 1, so the signal is weak.
- **Pack litter** (:189-190): one litter per group per year = packLitter·fed + U(0,1), only in the first week of the birth month and only if home ≥ 0. *Arbitrary*: it is a snapshot of one week's fed, ignores how many females there are, and a homeless or briefly lean pack skips the whole year.
- **Hunger death** (:198): (0.5 − fed)·0.3 **per step**, not × years. *Arbitrary*: it depends on the step length, has a cliff at 0.5, and ignores remaining fat except through the fed-lift.
- **Cold** (:200) 0.02·discomfort per step. *Arbitrary*: also not scaled to time.
- **Thirst** (:209-213). *Rough*. Hunters' water comes from fed, so starving also means thirsting. That is a plausible coupling.
- **Herd crowd disease** (:215-219), with ×5 for migrants. *Rough*. **Carnivore disease = 0** (:219). *Arbitrary*: there are no density-dependent losses for predators.
- **Homeless deaths 0.4/yr** (:222). *Arbitrary*: it applies to established packs that lost their home through hunger (via GIVE_UP), not only to dispersers.
- **Eagle risk ≤ 80 kg** (:224). *Rough*: a step function.
- **Death caps 0.9 / 0.95; young ×1.5 hunger** (:227-229). *Rough*.
- **Maturation** (:246): binomial(years/yearsToMaturity). *Grounded*.
- **Split at maxGroupSize into halves** (:258-261). *Rough*: a hard cap.
- **Merge small herds of the same tag and place up to max** (:285-297). *Rough*.
- **Pairing lone carnivores within 50 km** (:300-313). *Arbitrary*: a pack needs ≥ 2 adults to breed (:188), which imposes an Allee effect on solitary cats.
- **remember** (:317-323): a month's place is overwritten by the current place whenever fed > Q + 0.05. *Arbitrary in effect*: inherited months with Q < 0.95 are replaced by "where I was" in the first year, which locks herds in place.

### Bearings (HexBearings.kt)
- Near-country gain vector (60 km, fade 50 km), peaks in sight (√h horizon), climate plane with INSTINCT. *Grounded* in idea.
- Strength = (best − here)/best (:162). *Rough*: a well-fed herd feels no pull, even when next month's country is elsewhere.
- Monthly update from current quality (:128-174). The quality lambda uses `monthFit` (this month and next), but food is current. There is no forward-looking phenology.

---

## 2. Constants

### HexGroupRules.kt

| const (line) | value | meaning | verdict | defensible value / form, basis |
|---|---|---|---|---|
| SHIFT_DAYS (6) | 2 | days of net shift per week | rough | Keep for residents. For herds on a season trek use maxDailyTravel × ≥3 (migrants move 10–30 km/day for weeks; from memory). |
| SNOW_EFFORT_MM (8) | 150 | snow depth doubling effort | rough | Scale with species: ≈ 0.3·snowLimitMm or chest height (Telfer & Kelsall 1984; from memory). |
| AUTUMN_FAT (10) | 0.8 (0.3 otherwise) | fat laid down, days per day | rough | Herbivores: tie to food surplus, not to the calendar. Carnivores: no seasonal boost; use gorging surplus (leftMeat). |
| WELL_FED (12) | 0.9 | fed threshold for gain vs draw | arbitrary | Use continuous energy balance: fat += (eaten − need)/need·days, with surplus counted uncapped. |
| SWITCH (14) | 1 | stretch of a diet part, ×(1+SWITCH) | rough | 1–2 is fine, but it must apply to meat. See #1. |
| MARK_KEEP (16) | 0.85 | weekly freshness kept | grounded | Scent marks last weeks (from memory). |
| MARK_FRESH (17) | 0.3 | fresh enough to deter | rough | OK. |
| THEFT_RATIO (19) | 1.3 | ratio from which robbing happens | rough | Use continuous P = f(ratio). Hyenas need numerical superiority ~4:1 over lions (Cooper 1991; from memory). |
| THEFT (20) | 0.5 | chance the stronger robs | rough | Cheetahs lose ~10–14% of kills, leopards ~5–10% in Africa (from memory). Per-meeting 0.5 is OK only if meetings are rarer. |
| INTRAGUILD_KILL (21) | 0.1 | per-meeting kill chance (ratio > 2) | **arbitrary, too high** | ~0.01 per meeting for adults. Cubs/young first, at ~0.1. Lions are a main killer of cheetah cubs, rarely of adults (Laurenson 1994; from memory). |
| FIGHT_BACK (23) | 0.002 | per-chase death vs fully armed prey | grounded | Comment gives the basis. |
| NOT_WORTH_TRYING (25) | 0.05 | catchability below which a herd is not tried | rough | Replace with profitability: meat·P(catch)/time vs the current intake rate (optimal diet). |
| AVOID_STRONGER (27) | 0.3 | penalty on places held by a > 2× stronger party | arbitrary | Continuous: −k·P(intraguild death or theft) from a remembered risk map. |
| MIGRANTS_GATHER (29) | 5 | crowd tolerance multiplier for migrants | rough | OK. |
| MATE_SEARCH_KM (31) | 50 | lone hunter mate search radius | arbitrary | Male leopard/cheetah ranges reach 100s of km² (from memory). Better: let a lone female breed if any same-kind male is within ~2 territory radii. |
| SHELTER_EFFECT (33) | 0.7 | refuge strength | rough | OK. |
| AVOID_SKILL (34) | 0.6 | chance of slipping away given first detection | rough | OK. |
| TRACKING (36) | 8 | tracking multiplier on searched ground | arbitrary | Derive from encounter rates. Lions try a hunt every 1–2 days (from memory), so make TRACKING whatever gives 3–5 encounters a week at typical herd density. |
| CONTENT (38) | 0.95 | fed level to skip planning | arbitrary | Compute shortcut. Remove it for herds whose month's goal differs from here. |
| SHUN_HUNTERS (40) | 0.3 | herd avoidance weight | rough | Weight by the danger to this prey kind (catchable × predator need), not by kg. |
| HUNTERS_HALF_KG (41) | 1 | predator kg/km² at half shunning | rough | Plan feature f[3] (HexGroups.kt:375) uses 3, which is inconsistent. |
| HERD_GAME (43) | 0.3 | herd weight in the hunters' bearing value | rough | OK as a proxy. |
| PLANT_FOOD_WEIGHT, CAMP_SAFETY, LARDER_DAYS, LARDER_SPOIL, START_LARDER_DAYS, BAND_GIFT, BURN_OFF, CAMPFIRE (45-57) | - | people only | out of scope | CAMPFIRE changes lion and hyena kills of people only. |
| DRY_SOIL_MM (59) | 40 | soil water of a dry spell | rough | OK. |
| RAIN_AHEAD_MM (60) | 20 | rain that promises relief | rough | OK. |
| PREY_HALF (62) | 200 kg/km² | half-saturation of prey value | **arbitrary (one for all)** | Per kind: ≈ the group's weekly need / the area it can sweep, i.e. need_kg/(sweptKm2·CATCH). That gives lion ≈ 2.1, leopard ≈ 0.8, jackal ≈ 0.03 kg/km² for big game. Then add small prey and carrion with diet weights. |
| SEASON_PULL (64) | 1 | weight of the season's remembered place | rough | Fine once progress is per reach (#7). |
| BEARING_PULL (66) | 0.5 | weight of the bearing | rough | OK. |
| ASTRAY (68) | 0.5 | bearing strength that makes a group trek | rough | OK. |
| SEASON_RANGE_KM (70) | 120 | max distance of inherited season places | rough | Gazelle and onager move 50–300 km; reindeer 100s–1000 km (from memory). Make it per species: ~10× daily travel × 4. |
| STAY (71) | 0.15 | bonus for staying | **arbitrary, dominates** | ≤ 0.05, or scale with the effort cost already charged at :353 (double counting). |
| HUNGRY_LEAVE (73) | 0.5 | hungry pack's dislike of recent hexes | rough | A proxy for prey depression. Keep until wariness of residents exists. |
| GIVE_UP_WEEKS (74) | 4 | lean weeks before dropping the home | arbitrary | Do not drop the home; enlarge the range. Abandonment is real only after months (from memory). |
| MAX_CHASES (76) | 12 | chases per week | **dead**: assigned to an unused `tries` (HexMeetings.kt:52) | Remove, or use it as the handling-time limit. |
| CATCH (77) | 0.35 | success at catchability 1 | rough (one for all) | Per species, from memory: lion 0.25–0.3, leopard 0.15–0.4, cheetah 0.4–0.6, wolf 0.1–0.5 (big prey), jackal (rodents) ~0.3. |
| SEARCHING (79) | 0.3 | share of moving time spent searching | rough | OK. |
| MEAT_SHARE (84) | 0.6 | edible share of a carcass | grounded | 0.5–0.65 (from memory). |
| HOME_ADVANTAGE (85) | 1.5 | strength bonus at home | grounded | Residents usually win (from memory). |
| RIVAL_KILL (86) | 0.05 | death in a same-kind fight | rough | Lions: male coalition takeovers cause most adult male deaths and infanticide (from memory). There is none here. |
| MAX_MEETINGS (88) | 4 | meetings per group per week | arbitrary (compute) | Caps herd predation at 4 kills a week per herd. Raise it or make it per-day. |
| VIGILANCE (90) | 0.6 | chance an open-country herd sees a courser first | rough | OK. |
| WARY_GAIN (92) | 0.3 | wariness gained per chase | rough | OK. |
| WARY_KEEP (93) | 0.97 | weekly retention (half-life 23 weeks) | arbitrary | Risk effects fade over days to weeks: ~0.7 (half-life 2 weeks; from memory). |
| HIDING_KG (95) | 70 | max mass that hides in cover | arbitrary step | Continuous: hide = 1 − HIDING·cover·(1 − M/(M+70))·(1 − stalking). |
| HIDING (96) | 0.8 | cover's cut to the hunter's chance | rough | Must not apply to stalkers. Cover aids ambushers (leopard success higher in dense cover; Balme et al. 2007, from memory). |
| MOON_DARK (98) | 0.3 | moon effect on stalkers | grounded | Packer et al. 2011 (from memory). |
| DISPERSAL_DEATHS (100) | 0.4/yr | extra deaths of homeless packs | rough | Real dispersers lose ~0.3–0.6/yr (from memory), but this must apply only to dispersers (split-offs), not to residents whose home was dropped by GIVE_UP. |
| EAGLE_PREY_KG (102) | 80 | max mass whose young eagles take | rough | It is the young's mass that matters. OK. |
| SURPRISE (103) | 1.5 | success bonus when unseen | rough | Always applied (see :373). Fold into CATCH. |
| FIGHT_RATIO (105) | 1.5 | ratio for giving way without a fight | rough | OK. |

### Inline magic numbers

| file:line | value | meaning | verdict | better form / basis |
|---|---|---|---|---|
| HexGroups.kt:49 | 2·daily; 0.3 if waterFromFood ≥ 0.8 | water distance in the hunter bearing | arbitrary (special case) | Multiply by (1 − waterFromFood) continuously. |
| :52 | min(1.5, suits)/1.5 | habitat cap in the herd bearing | rough | - |
| :55 | 0.3; 0.5 | food half-saturation; hunter penalty | rough | The same 0.3 is at :340. Name it. |
| :97 | 50 km² | default start density | arbitrary | - |
| :106 | groupSize/3 | start young | rough | - |
| :107 | 0.5·maxFat | start fat | ok | - |
| :274 | 0.5·daysWithoutWater | thirsty threshold | rough | - |
| :277-278 | fed 0.5 / 0.7; discomfort 0.5 | restlessness | arbitrary | One rule: restless when expected intake here < need (or fat falling). |
| :281 | every 2nd week | content skip | arbitrary | See CONTENT. |
| :320 / :321 | fed 0.5 / 0.3 | territory and marks respected | arbitrary | Continuous: penalty ∝ fed. |
| :332 | 2× | danger threshold | arbitrary | See AVOID_STRONGER. |
| :333, :340 | 0.5 | crowd weight | rough | - |
| :342, :346, :347, :348 | 0.5, 0.5/10, 0.5, 0.3 | discomfort and forecast weights | rough | - |
| :350 | 0.02 + 0.3·dry/days | water distance cost | rough | - |
| :351, :352 | −1 | no water when thirsty; deep snow | rough | - |
| :353 | 0.1 | distance cost | rough | Also double counts with STAY. |
| :359 | 0.3·Q | baseline season draw | arbitrary | See #7. |
| :375 | 3 | predator half kg in the learned feature | inconsistent | Use HUNTERS_HALF_KG. |
| :398 | 0.02 | tie noise | ok | - |
| :412 | +5 km; startKm2·groupSize | territory radius | arbitrary | Species.territoryKm is unused. Radius should come from prey: A = need/(prey production·share). |
| :420 | ×3; 10 | crowd "usual" | rough | - |
| HexMeetings.kt:29 | 1.5 | prey up to 1.5× the pack mass is fully catchable | rough | Use actual adults. |
| :30 | 0.05; 0.3 | too-small prey | rough | - |
| :33 | 0.5 | shelter vs scent hunters | rough | - |
| :58 | min(2, path.size) | sweep multiplier for movers | arbitrary | Remove: the total sweep is fixed by the week's going. |
| :63 | min(area, sweep) | cap when staying | rough | OK physically. The lion then sweeps its hex thoroughly. |
| :69 | 0.5 + 0.5·overlap | prey found resting | rough | - |
| :72 | /0.6 | overshoot of kills wanted | arbitrary | Remove. Ask for the meat needed. |
| :74 | 0.5 | max share of residents killed per week | arbitrary | Use a Type II/III response instead. |
| :80, :395 | 0.3·mass; 0.5 | young meat; young pick | rough | Young pick ∝ young share × their higher catchability. |
| :98, :100 | 0.5 | carrion/bones cap per pass | rough | Plus the accounting mismatch (see §1). |
| :321 | max(span, 0.5) | min meeting span | ok | - |
| :323 | 0.5 + 0.5·hours | hunter–herd hour overlap | rough | - |
| :339-340 | 0.5; 0.5; cap 0.9 | slip-away terms | rough | - |
| :345 | 0.5 | openness default | ok | - |
| :359 | cap 0.95 | vigilance | ok | - |
| :373 | 0.5·wary | wary herd's cut | rough | - |
| :428 | 2 | ratio for intraguild kill | arbitrary | Continuous. |
| :432 | 0.7 | share of meat stolen | rough | - |
| :440 | 0.5·mass | meat from a killed rival | rough | Lions seldom eat leopards or cheetahs (from memory). ~0. |
| HexLifeAndDeath.kt:42 | (t−2)/13, 0.1..1 | growing index | rough | Use simulated green/NPP. |
| :44 | −1 °C; grass > 0.3; 0.2 | snow factor | arbitrary | Use snowpack climatology vs snowLimitMm. |
| :47 | 0.2 + grow; /(1+dist) | inherited score | rough | - |
| :173 | months 7..10; 0.3 | fat season | rough | See AUTUMN_FAT. |
| :176, :179 | month 3; 0.1 | birth-month drift date and margin | rough | - |
| :190 | packLitter·fed + U | litter | arbitrary | Per breeding female: females·littersize·P(breed | fat), over gestation. |
| :198 | 0.5; 0.3 per step | hunger death | **arbitrary, not ×years** | Death hazard per day when fat = 0 and intake < need: e.g. h = k·(1 − fed)·days/maxFatDays (k ~ 1). |
| :200 | 0.02 per step | cold death | arbitrary (not ×years) | Scale with days. |
| :209 | 0.5·daily | drink reach | rough | - |
| :212 | 0.5 + 0.5·green | water from plants | rough | - |
| :213 | 0.05/day; cap 0.5 | thirst death | rough | - |
| :215 | 0.1 | default usual density | arbitrary | - |
| :219 | 0 for carnivores | no predator disease | arbitrary | Apply HexFauna.DISEASE·crowd² with the carnivore usual density (mange, distemper, rabies). |
| :227 / :229 | 0.9 / 0.95; 1.5× | death caps; young hunger factor | rough | - |
| :241, :252 | 0.3 | young carcass mass | ok | - |
| :259 | halves | split | rough | - |
| :321-322 | 0.05; 0.3 | memory overwrite margin; learning rate | arbitrary in effect | See #7. |
| HexBearings.kt:188-197 | NEAR_KM 60, FADE_KM 50, PEAK_M 600, PEAK_SPACING 30, MASSIF 15, FAR 200, CLIMATE 250, INSTINCT 0.5 | perception ranges | rough | NEAR_KM should be species-scaled (≈ 2–4 × daily travel). A jackal and a reindeer know the same 60 km. |
| HexBearings.kt:90 | 3.57·√h | horizon distance | grounded | Geometry. |
| HexBearings.kt:106 | 6.5e-3 | lapse rate | grounded | - |
| HexBearings.kt:167-168 | /10 | cold/hot scaling | rough | - |

Dead or unused: `MAX_CHASES` / `tries` (HexMeetings.kt:52); `Species.territoryKm` (set per kind in Catalogue, ignored by `territoryKm(s)` at HexGroups.kt:412); `r` (HexLifeAndDeath.kt:29).

---

## 3. Interactions present and missing

### Present
- Hunters share and deplete the resident prey fields (exploitation competition). Leftover meat and kill remains feed carrion, then hyenas and jackals (HexMeetings.kt:83, :195).
- Herds' wariness after chases lowers success (:373, :392). Moonlight shapes stalkers (:36).
- Intraguild theft, killing and avoidance (danger term; slipsAway).
- Herds shun predator kg (HexGroups.kt:339-340). Predator kg also enters the herd bearing (:54-55).
- Snow, temperature and forecast shape where groups go. Thirst is coupled to hunters' food (HexLifeAndDeath.kt:212).

### Missing or mis-wired (why it matters)
1. **Meat cannot substitute for other diet parts** (HexMeetings.kt:50/60/65 with :136). Hunting stops at the meat quota, so SWITCH for meat only uses the accidental overshoot. Lions get large overshoots from big kills; gazelle and hare killers get none. *This is a direct cause of small-hunter hunger and lion advantage.*
2. **Small hunters value ground by big game only** (HexGroups.kt:223, :329, :47). Jackals (meat 0.1), hyenas (0.1) and leopards (0.6) choose places and bearings by prey they hardly eat, so they end up where lions are, not where rodents and carrion are. PREY_HALF is the same for all, which flattens their choice.
3. **Cover helps the wrong side** (HexMeetings.kt:34). Prey < 70 kg hide from every hunter, stalkers included, while lions' > 70 kg prey never hide. Leopards in their own habitat lose up to 80% of their catchability.
4. **Intraguild predation is too frequent and kills adults** (HexMeetings.kt:316-323, :438). It also never targets young, and the weak never harm the strong's young (no hyena mobbing of lion cubs). Lion-side encounter rates of ≈ 1 a week and 10% kills remove whole leopard, cheetah and jackal populations near lions.
5. **No landscape of fear over time.** Avoidance uses last week's top party only (HexGroups.kt:155-159, :332). There is no memory map of where lions were over weeks. Leopards and cheetahs are pinned to their territory while fed ≥ 0.5 (:320), so they cannot shift away from a resident pride. Temporal partitioning exists only in meetChance's hour overlap, not in hunting (cheetah day vs lion night does not change resident-prey competition).
6. **Territory does not scale with prey** (HexGroups.kt:412). Real ranges scale inversely with prey biomass. Here a lean pack is confined to fixed ground, then after GIVE_UP_WEEKS (HexLifeAndDeath.kt:116) loses its home. That means no births (:190 requires home ≥ 0) and +0.4/yr deaths (:222). Hunger turns into extinction through rules, not biology.
7. **Lion regulation is absent.** There are no carnivore crowd losses (HexLifeAndDeath.kt:219), no infanticide or takeovers in rivalsMeet, nothing preys on lion young, and big-cat youngMortality defaults to the adult rate (Catalogue: lion youngMortality = 0.1/yr; real first-year cub mortality is ~0.5–0.7, from memory). With people absent, only food limits lions, and they win food competition (largest sweep 346 km², `catchable` size = 1 for everything ≤ 500 kg, robbing, no avoidance).
8. **Functional response is Type I with a hard quota** (HexMeetings.kt:70-74). There is no handling time (prey density does not saturate kills), no low-density refuge (Type III), and resident prey do not become wary (behavioural depression). Predators can strip a hex to 50% a week and then crash. Prey choice follows catalogue order (:64), not profitability, so a lion keeps killing the first-listed kind.
9. **Group size does not shape hunting.** The sweep is per group (`sweptKm2` has no adults term) and catchability uses the species' nominal pack (:28). A pride of 12 sweeps like a pride of 2 but needs 6×, and a lone lion catches aurochs like a pride. Real per-capita intake vs group size (and kleptoparasitism defence) is what sets pride size.
10. **Seasonal migration logic is self-cancelling** (HexGroups.kt:281, :282, :359-362; HexLifeAndDeath.kt:317-323).
    - A fed herd skips planning every other week.
    - When it does plan, it uses the slow reach.
    - Season pull ≤ 0.075 is below STAY = 0.15.
    - `remember` overwrites inherited months with wherever it is, once fed > Q + 0.05.
    - fed saturates at 1, so it cannot tell "enough" from "rich".

    Only reindeer migrate, because cold, snow and discomfort make them *restless* (bigger reach) and drive the INSTINCT bearing. Missing: memory that keeps the inherited round unless it is clearly worse; a forward-looking pull to next month's place; using forage quality (nourishment) rather than capped fed as the memory signal.
11. **Herds' danger is by predator kg, not by risk to that prey** (HexGroups.kt:339). Herd memory (seasonQ = fed) ignores predation, so herds never learn to leave lion ground seasonally.
12. **Small-game and carrion accounting leak** (HexMeetings.kt:97-98, :143, :153). Density falls over the whole place but only the swept share is credited. Moving jackals, hyenas and leopards waste stocks they depend on.
13. **Gorging and kill-size buffering are absent.** Leftover meat goes straight to carrion (:195) instead of fat. Small hunters live hand to mouth; lions effectively feed the scavenger guild, while their own surplus counts against SWITCH only.
14. **Breeding needs a "pair group"** (HexLifeAndDeath.kt:188, :300-313). Solitary felids (leopard, cheetah) suffer an imposed Allee effect when a pair loses one adult, worst where they are sparse (MATE_SEARCH_KM 50).

---

## 4. Top 10 problems, ranked by distortion

1. **Meat quota blocks diet switching** (HexMeetings.kt:50, :60, :65, :368 vs :136). Small hunters starve when small prey or carrion fall short; lions are buffered by lumpy overshoot.
   *Fix (reshape):* run small game, carrion and fruit first. Then hunt big game for `min(wantAll − othersGot, (1+SWITCH)·need)` and stop on that. Use the same target at :368.

2. **Intraguild killing ≈ 10% per co-occupied week, adults only** (HexMeetings.kt:438; meetChance :316-323). This erases leopard, cheetah and jackal pairs near lions, wolves and hyenas.
   *Fix (tune + reshape):*
   - INTRAGUILD_KILL → ~0.01 for adults, with ~0.1 hitting young first (`weak.young--` before adults).
   - Make the chance continuous in ratio rather than a step at > 2.
   - Scale meetChance for carnivore pairs by actual home-range overlap (a pack's swept area is not all "meeting" ground). E.g. divide by territory area, not hex area.

3. **Small hunters choose ground by the wrong food** (HexGroups.kt:223, :329; bearings :45-51), and PREY_HALF is one value for all.
   *Fix (reshape):* placeValue for carnivores = Σ diet-part × its stock (big game catchable kg, small-life kg, carrion kg, fruit), each divided by that part's weekly need per km². Use a per-kind half-saturation (≈ need/sweptKm2). Use the same in the bearings quality lambda.

4. **Cover penalises stalkers** (HexMeetings.kt:34).
   *Fix (reshape):* hide = 1 − HIDING·cover·(1 − s.stalking)·smallness(M), with a continuous smallness instead of the 70 kg step. Optionally give stalkers a cover bonus via the existing `speed` term (approach cover). Basis: leopard success rises with cover (from memory).

5. **Fixed territory, then GIVE_UP → homeless → no births + 0.4/yr deaths** (HexGroups.kt:320, :412; HexLifeAndDeath.kt:116, :190, :222).
   *Fix (reshape):*
   - Territory radius from prey: r = sqrt(need·weeks/(π·placeValue)), clamped to travel.
   - Hunger enlarges the territory instead of dropping the home.
   - DISPERSAL_DEATHS only for split-offs that never held ground.
   - Breeding allowed when fed and fat suffice, whatever the home.

6. **Lions are unregulated** (HexLifeAndDeath.kt:219; rivalsMeet HexMeetings.kt:470-485; Catalogue youngMortality defaults).
   *Fix:* set big-cat and wolf youngMortality to ~0.5–0.7/yr in Catalogue (tuning). Apply the existing crowd-disease form to carnivores (reshape, with carnivore usual density). When a pack loses a fight at home, the winner kills the loser's young (infanticide; a reshape of rivalsMeet).

7. **Seasonal migration suppressed** (HexGroups.kt:281, :282, :359, :362; HexLifeAndDeath.kt:321).
   *Fix (reshape + tune):*
   - Progress = (fromHere − toGoal)/min(fromHere, reach), so a full-reach step toward the goal earns the whole pull.
   - STAY → ~0.05.
   - Treat "goal ≠ here" as restless (forced-march reach) and skip the CONTENT shortcut for it.
   - In `remember`, replace a month's place only if fed·nourishment beats Q by a margin for two years running, or blend Q down slowly instead of jumping to the current place.
   - Use an uncapped quality signal (got/want before clamping).

8. **Sweep rules** (HexMeetings.kt:58, :63; sweptKm2 HexGroupRules.kt:82). Moving gives 2× ground, the sweep is independent of group size, and `catchable` uses nominal pack size (:28).
   *Fix (reshape):*
   - Total sweep = sweptKm2 split over the path (drop min(2, …)).
   - Sweep grows with adults^0.5 (searchers fan out).
   - catchable `pack` = g.adults·massKg, so lone lions are weaker and big prides take aurochs.

9. **Type I response, catalogue-order prey choice, no handling time** (HexMeetings.kt:64-74).
   *Fix:*
   - Loop prey kinds in order of profitability (meat·catchable), or allocate chases ∝ encounter·catchable (reshape).
   - Add handling time per kill (e.g. 0.5–1 day for a large kill) so kills = aN/(1 + a·h·N). The dead `MAX_CHASES` can become the handling budget (reshape of an existing constant).
   - Wariness of resident prey after heavy hunting is a **NEW mechanism**.

10. **Hunger mortality and fat rules** (HexLifeAndDeath.kt:172-173, :198).
    - The hunger death rate is per step (not × years), with a cliff at fed 0.5.
    - Fat is gained only above fed 0.9.
    - Carnivores get the autumn rate, and leftover meat never becomes fat.

    *Fix (reshape):* use a continuous energy balance (fat += (eaten − need)/dailyNeed, with eaten including gorged leftover meat up to a gut limit). Hunger hazard = k·max(0, deficit)·days only when fat = 0. Seasonal fattening only for herbivores and bears.

Also fix the small-game and carrion accounting leak (HexMeetings.kt:97-98, :143, :153): subtract only swept/area × take from the density. Remove dead `MAX_CHASES`/`tries`, and use or remove `Species.territoryKm`.
