# Model audit, 14 October 2026

This audit covers every rule and constant in the hex model, checked against real biology and the record, and every link between features, including the links that are missing. Five detailed reports sit beside this file:

1. [Weather, water, plants and plant food](1-weather-water-plants.md)
2. [Resident plant-eaters and every species' values](2-residents-and-species.md)
3. [Small life and water life](3-small-life-and-water-life.md)
4. [Herds and hunters](4-herds-and-hunters.md)
5. [People](5-people.md)

Each report lists:
- every rule, with a verdict: grounded, rough or arbitrary;
- every constant and inline number, with its basis or a better value;
- the links present and missing;
- its top ten problems.

The report writers worked from the code alone; nothing was run. Literature values are from memory. I spot-checked the claims that matter most against the code: the seasonal-weather bug, carrion rotting before scavengers eat it, migrant geese whose breeding months never fall while they are present, the meat quota, and herds' seasonal memory being overwritten. All held.

## What is wrong, by kind

### A. Matter is not conserved, so the system is not closed

- **Plants:**
  - Fruit, seed and straw appear without leaving the plants.
  - Plant turnover goes to litter while the plants keep it.
  - Eating dead straw removes live grass.
  - Eroded soil fertility vanishes.
- **Animals:**
  - Fat is laid on without eating more.
  - Births, milk and growth cost no food.
  - Hares, hyrax, game birds, tortoises, shellfish and river fish grow towards hand-set ceilings without eating anything. They never compete with the herds.
- **Fish:**
  - Sea fish convert food into growth at about 67%, against 10–25% in reality.
  - The spawning run is rebuilt every week from nothing.
- **Movement:** animals and fish spreading between places are averaged over unequal areas, which creates and destroys mass.

### B. Fixed quotas and hard caps where real feeding curves belong

- **Diets:**
  - Hunters' meat hunting stops at the meat share of the diet. The new diet switching can therefore fall back on meat only when a kill was bigger than needed. Lions always have such leftovers; gazelle and hare killers almost never do. This is the strongest remaining reason small hunters starve and lions win.
  - Catches are fixed shares: small game 50% per call, foxes and eagles 10%, each sea predator 6% a week whatever its numbers, and people's fishing 1–30%.
- **Intraguild killing:** two hunters of different kinds in the same hex for a week almost always meet. Whenever one is more than twice as strong, it kills an adult of the other 10% of the time. A leopard pair living by a pride loses about four adults a year.
- **Territories and fire:**
  - Territories have a fixed size. After four hungry weeks a pack loses its ground, cannot breed, and dies faster. Hunger thus becomes extinction by rule.
  - Fire may spread 60 places in a week, about ten times a real six-day run, behind four on/off gates.
- **People:** desert kites take 40% of a passing herd per drive whatever the band needs, and kites are also later than the Natufian.

### C. The calendar decides what weather should decide

- **Fixed months:**
  - Fruit, seed and roots ripen in set months at every height.
  - Fattening follows the calendar, not the food.
  - Hibernation is free.
- **Ignoring the weather:**
  - Forage is dormant below 4 °C but never in the summer drought, which is the real lean season in the Levant.
  - Drying follows the monthly normal, not the week's heat.
  - Rain never has dry weeks, and deserts never have bust years.

### D. One value for every size and place

- **Disease:** every resident kind has the same "usual" density, 0.5 per km². Crowding disease therefore holds roe deer, ibex and mouflon far below their natural numbers and never touches aurochs or hippos. A density-by-mass rule already exists in the code but nothing calls it.
- **Uniform values:**
  - All soils hold 150 mm of water.
  - All hunters share one prey half-saturation.
  - A hunting party searches the same ground whatever its size.
- **Cover:** cover hides prey under 70 kg from every hunter, stalkers included. This takes up to 80% of a leopard's chances in its own habitat.

### E. Bugs and accounting errors

1. Severe winters and heat waves fell in autumn and spring after the calendar change. **Fixed** in this session.
2. Carcasses rot before vultures and scavengers feed. The rot is linear and too fast: everything is gone in a week at 28 °C.
3. Greylag geese breed in months when they are away, so they never breed. All migrants lose 30% per absence and recruit nothing while away.
4. Each week every death goes to the single largest cause. People's "30% crowding sickness" is partly this.
5. Herds' memory of their seasonal places is overwritten by wherever they are now. Their pull toward those places is weaker than the bonus for staying, so herds do not migrate. Only reindeer do, because cold makes them restless.
6. Bands can never be "content", so they never settle: the stay-when-content shortcut can't apply, and camp weeks, fouling and stores can't build up properly.
7. Small game and carrion are taken from a whole hex but only the searched part is eaten, which wastes the stocks small hunters need.
8. The seasonal temperature lags about 15 days, and the food stocks read the month one step ahead.
9. Dead constants and fields: `MAX_CHASES`, `territoryKm`, `flightKm`, `dangerToHumans`, `products`, `fearOfPeople`.

### F. Species values against biology

These are mostly parameter fixes; the second report has the values:
- Comfort temperatures are set for temperate Europe: fallow deer, aurochs, red and roe deer, boar and hippo.
- Snow limits are too high for elk, red deer, mouflon and boar.
- Wild boar have 8 young a year.
- Age at first birth is set as age at first mating for roe, fallow, red deer and hippo.
- Mountain gazelle is listed as migratory, though it is resident.
- Intake is about 30–50% below what free-living animals eat.
- **Cub and pup deaths fall back to the adult rate for big cats and wolves.** Lions lose 10% of cubs a year, against 50–70%.
- People's ordinary adult deaths are twice the record, and adults turn into elders at random, not by age.

### G. Links missing that real ecology has

- **Predators and prey:**
  - Prey don't avoid ground where hunters killed lately.
  - Starving and young animals are no easier to catch.
  - Small hunters choose ground by big game, not by the small prey and carrion they live on.
- **Water:** it is never drunk down. Floods don't wet the soil. Water draining into closed basins vanishes.
- **Waste:** rotting carrion doesn't feed insects. Seabird guano doesn't reach the land.
- **Weather and plants:** drought and frost don't spoil fruit set.
- **Lions:** nothing regulates them: no disease among carnivores, no killing of cubs when a pride is taken over, and cub deaths at the adult rate.

## Proposed order

**Stage 1: bugs and accounting.** No rule changes:
- feed scavengers on fresh carrion, before it rots;
- fix the geese's breeding months;
- split deaths among causes by each cause's share;
- take small game only from the ground searched;
- fix the seasonal-temperature lag and the food stocks' month;
- let bands be content, so they can settle;
- remove the dead constants and fields.

**Stage 2: parameters.** Existing values only:
- species values (temperatures, snow, litters, ages, cub deaths, intake);
- intraguild killing near 1% for adults and higher for young;
- people's ordinary deaths about 1% a year;
- near-universal customs (marrying out, sharing, medicine) held by all bands from the start;
- slower word drift;
- smaller loads for bands;
- desert kites off for a Natufian start.

**Stage 3: reshaping existing rules.** No new mechanisms, but rules change form:
- big game hunted for what the diet still lacks, up to the switching limit;
- feeding curves with handling time instead of fixed catch shares;
- small hunters choosing ground by their whole diet;
- cover helping stalkers;
- territory size set by prey, with hunger enlarging it instead of costing it;
- "usual" density by body mass;
- straw from drying grass, and dormancy from drought;
- matter conservation in plants, food and litter, and in moving animals and fish;
- fat laid on from food actually eaten;
- herds' seasonal memory blended, not overwritten, and a pull strong enough to move them.

**Stage 4: new mechanisms, for discussion only:**
- weekly wet and dry rain;
- mast years;
- runoff from heavy rain on bare ground;
- lake levels in closed basins;
- a deep-sea nutrient pool;
- prey avoiding ground where hunters killed lately;
- a digestive type (ruminant or horse-like) for how intake answers poor forage;
- water stocks that are drunk down;
- killing within bands;
- bands merging with kin.

Stages 1 and 2 fall within "tuning existing parameters". Stage 3 changes how existing rules work without adding new ones. I suggest stages 1–2 now, then stage 3 one item at a time, each checked with a 30-year run against the species list and the record.
