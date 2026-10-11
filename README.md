# Inflation

A simulation of how societies, power, debt and money arise, built up from late stone-age groups.
The design brief lives in the project's Claude Doc. This repository starts with the physical world.

## The world

`inflation.world` generates a 1000 × 1000 km region on a 1 km grid (one million cells), spanning
roughly 40°N to 31°N: temperate in the north, hot and dry in the south. The region is an
archipelago of larger landmasses and islands with ocean on every side.

### Generated once

| Layer | Model |
| --- | --- |
| Tectonics | 14 plates on a warped Voronoi map, about half continental (preferring the centre). Continental crust varies in thickness, so parts are drowned into shelf seas between islands. Collisions raise mountain belts, rifts open valleys. Ocean floor deepens with distance from spreading ridges (2.5 to 4.5 km); oceanic plates sinking under others leave trenches. A band along every edge is ocean floor |
| Volcanoes | Arcs 90–160 km behind trenches (continental arcs or island arcs), hotspot chains that age along the plate's motion (active shield at one end, drowned seamounts at the other), and rift cones. Old edifices are eroded with the land, young ones are built afterwards |
| Erosion and sediment | Stream-power law (implicit Braun & Willett 2013 solver, 40 steps × 50,000 years) and hillslope diffusion. Eroded material is carried downstream: rivers drop what exceeds their capacity, closed basins fill, large rivers build delta plains, and the rest builds a sloping shelf out to a shelf break |
| Geology | Rock type per cell (sets erodibility and soil), sediment thickness; flint, clay, obsidian, ochre, salt, copper, tin, bitumen |
| Climate | Monthly normals: latitude, 6.5 °C/km lapse rate, continentality; westerly moisture recharged over open sea, orographic rain and rain shadows; Mediterranean dry summers in the south |
| Rivers and lakes | Thornthwaite evapotranspiration, Fu–Budyko runoff, D8 routing; lakes from a water balance, closed basins turn saline. Valley floors are planed flat beside rivers (width grows with the square root of discharge). Rivers are classed creek, stream, river and great river; reaches with a third of a metre of water and no rapids are navigable by canoe or raft |
| Floodplains | Height above the nearest river (HAND); land a large flood (about ten times mean flow) reaches is floodplain |
| Terrain | Ruggedness from slope, local relief and a rock roughness the 1 km grid cannot resolve (lava fields and karst roughest, alluvium smoothest, buried under thick sediment). Walking effort from Tobler's hiking function, roughness, wetlands and river crossings; open water is impassable on foot. Line of sight over the terrain in 16 directions out to 30 km with earth curvature; prominence over the surroundings; defensibility from height, broken ground and approaches cut by water, marsh or steep slopes |
| Biomes | Potential vegetation from aridity, temperature and drainage, including marsh, swamp forest, bog, salt marsh, mangrove and floodplain forest |

### Over time (`Simulation`)

One step is 6 days (5 steps per month), configurable in `WorldConfig`.

| Process | Model |
| --- | --- |
| Calendar | 12 months × 30 days; the moon is new on day 1 and full on day 16; the year starts at the spring equinox; day length by latitude |
| Weather | Coarse autoregressive temperature and rain anomalies (about five days' persistence), wet and dry years, a quasi-periodic ~5-year oscillation and a decadal swing with their own spatial patterns. Shocks: multi-year droughts (about 1.5% chance a year), severe winters (6%), heat waves (8%), and volcanic winters after VEI 5+ eruptions. Snowpack with degree-day melt |
| Water | Soil bucket (150 mm), groundwater and baseflow; runoff routed down the network every step. Rivers above bankfull (4× mean) flood their floodplains |
| Waterworks | Dams with reservoirs that fill and release steadily, canals that divert part or all of a river (must run downhill and cannot loop), irrigation onto fields, and permanent rerouting of a river |
| Eruptions | Active volcanoes erupt at their own rates; explosivity follows the VEI frequency distribution; ash falls downwind, smothers vegetation and enriches soil |
| Visibility and going | Visibility on the ground is a function of ruggedness and vegetation: broken ground, scrub, forest and reed beds hide anything beyond tens to hundreds of metres; open smooth ground sees as far as the terrain allows. Thickets, undergrowth and snow add to the walking effort |
| Vegetation | Herbs, shrubs and trees per cell, growing towards a ceiling set by recent moisture, warmth and soil. They die back under drought, frost, deep floods, ash and fire, and regrow: herbs within a season, trees over decades and only from nearby seed. Grazing, browsing and felling go through `consume`; animals' passage through `trample`, which wears trails |

### Animals (`inflation.ecology`)

Animals come in three levels of detail. Small, numerous life is a density field per cell; large
animals live in groups (herds, packs) that decide each day where to go. Group behaviour goes
through a `Policy`: a hand-written baseline, or small networks trained to copy it and then to
improve on it (see Learned behaviour below).

| Layer | Model |
| --- | --- |
| Plankton | Nutrients, phytoplankton and zooplankton in sea and lake cells, stepped daily. Nutrients are rich on shallow shelves and at river mouths, poor over deep water; cold water mixes nutrients up, warm water stratifies. Gives a spring bloom and coasts far richer than open sea |
| Fish | Resident fish in sea and lakes follow primary production (shelves and river mouths far richer than open water); river fish scale with channel size and warmth, shrink with low water, die where the bed nearly dries and come back from downstream. Shellfish beds within about 3 km of the shore in water under 10 m. A spring spawning run (shad-like) gathers off each river mouth and moves upstream about 20 km a day for two months, through lakes, until a cascade or a dam stops it |
| Soil life | Plant litter from turnover, die-back, frost, floods and ash decomposes at a rate set by warmth and moisture; part becomes soil organic matter, which raises the fertility plants grow on. Carcasses rot in days to weeks and feed scavengers |
| Small animals | Plant-eating insects (active above 10 °C), biting insects (in warm wetlands and floodwater) and rodents (logistic on food, hit by cold and snow), in kg/km², spreading to neighbouring land |
| Water | Ground wetness per cell from soil moisture, recent rain, shade, floods and wetland. Animals drink at rivers, fresh lakes, floodwater, the pools and seeps of very wet ground, and at springs. In dry country, rain that sinks in upslope flows underground and comes up as year-round springs and water holes at the foot of hills, along faults and from limestone; the largest in the driest land are oases that keep the ground around them damp. Herds also get water from fresh food and dew, browsers and gazelles most, aurochs and onagers least, so in the wet season water is everywhere and in the dry season herds gather at rivers and springs |
| Large herbivores | Gazelle, roe, fallow and red deer, ibex, wild boar, aurochs, onager and hippo, each with its own diet (grass, browse, roots and mast), favoured ground (cover, rugged, wet), running speed and defence against predators. Hippos keep within 1.5 km of water. Each day a herd walks as far as a day's effort takes it over the terrain and weighs forage, water, snow, crowding, its favoured ground and the scent or sight of predators that could kill them. Where a day's walk finds far too little food, or no water for a thirsty herd, it sets off on a march instead: about 60% of its longest day's travel, day after day, eating a third of its need on the way. Young growth regrows over a month; in lean months they fall back on standing dead grass and twigs, a stock that builds up over months and is eaten down where herds crowd, so the dry season sets how many grazers the land carries. Big animals digest it better (Jarman-Bell), pigs poorly, and only as much as fits in the gut. Births in season, scaled by condition; most piglets die in their first year |
| Predators | Wolf (packs of 3, coursers), lion (prides of 5, which take the largest prey and rob everyone), leopard (pairs, stalkers of broken and wooded ground), striped hyena (mostly a scavenger) and golden jackal (pairs living on rodents, carrion and fawns). Each follows the scent of prey it can catch and avoids much stronger predators, which kill weaker ones now and then and drive them off. Groups chase herds within 4 km: the young and weak are easier to catch, big herds spot them sooner, a courser must outrun its prey while a stalker needs cover, horns and bulk deter a few hunters more than a big pack or pride, and hunters much lighter than their quarry rarely bring it down. Big, armed prey fight back: an adult boar, aurochs or red deer brought down sometimes kills a hunter. A kill draws rivals, and a stronger group nearby takes most of it. Scavengers eat carrion first. Groups of a species that meet contest their ground, so each keeps a territory |
| Crocodiles and eagles | Not tracked as animals. Crocodiles live in warm rivers and lakes; a herd drinking there may lose one, the young most often, except in the cold months. Eagles hunt over cliff country and take the young of the smaller hoofed animals in the open |

Body mass sets food needs (Kleiber scaling) and starting densities (Damuth's rule). The species
figures are mid-range literature values recalled from memory, not yet checked against sources.

On 400 km test islands (seeds 1, 3, 7 and 11, 21,000–38,000 km² of land), 13 of the 14 species
persist over 20 simulated years on all four; lions die out on two of them. After 20 years, per km²
of land: ibex 0.9–1.4, wild boar 1.0–1.7, roe deer 0.2–3, gazelle 0.2–1.4, red deer 0.1–0.25,
aurochs 0.1–0.25, fallow deer 0.03–0.08, onager up to 0.05, and 180–240 hippos per island. Per
predator: one wolf per 120–245 km², one leopard per 600–1,900 km², one striped hyena per 340–700 km²
and one jackal per 95–140 km². Wild boar are killed most often, then roe deer, ibex and gazelle. In the dry season
10–30% of herds are on the march.

![animals](docs/img/fauna.png)
![visibility](docs/img/visibility.png)

Groups decide where to go in parallel, so the animal step uses every CPU core. On a 16-thread
desktop a 6-day step takes about 65 ms on the 400 km test island and 0.8 s on the full 1000 km map
(about 1.3 million animals), of which the animals take 90%.

Measured on seed 7 (`summary.txt`): no lowland below 300 m keeps snow lying even at 40°N. Land at
300–1000 m has it about 10 days a year in the north, land at 1000–2000 m 80–120 days, and mountains
above 2000 m most of the year.

![relief](docs/img/relief-seed7.png)
![biomes](docs/img/biomes-seed7.png)
![rivers](docs/img/rivers-seed7.png)

## Learned behaviour (`inflation.learning`)

Each species can decide with a small network (17 inputs, two hidden layers of 32, about 1,700
weights) that scores each place the group could go from the same inputs as the hand-written
baseline. Plain Kotlin, no dependencies; groups decide in parallel on the CPU.

1. **Copying the baseline.** `imitate` runs the world, records a sample of decisions with the
   baseline's score for every option, and fits each species' network to those scores. Held out,
   the networks pick the baseline's best option 90–99% of the time, and populations over 20 years
   stay close to the baseline's. The copies are in `policies/`.
2. **Evolving wolves.** Evolution strategies: each generation draws random directions in weight
   space and tries each in both signs; every wolf pack in a world gets one variant, passed on when
   the pack splits, so one world run compares all of them. Fitness is the wolf-days a variant's
   lineages live per pack they started with: survival and offspring, with no reward for any
   particular way of hunting. Prey keep the baseline. EVOLVE_PLACEHOLDER

## Running

Requires Gradle 9 (the wrapper is included); a JDK 21 toolchain is downloaded if it is missing.

```
./gradlew test
./gradlew run --args="--seed 7 --out output/seed7"
./gradlew viewer -PviewerArgs="--seed 7"
```

`run` writes PNG maps (relief, biomes, geology, minerals, sediment, rivers, precipitation,
temperature, soil) and `summary.txt`, including snow cover from three simulated years.
Generation takes 45–60 seconds for the full region; `--size 400` makes a smaller one.

`./gradlew fauna -PfaunaArgs="--seed 7 --size 400 --years 25"` runs the living world and prints
animal populations, kills, food and soil each season.
Add `--policies policies` to let the species with a saved network use it.

`./gradlew imitate` records the baseline's decisions and trains one network per species to copy
them, into `policies/`. `./gradlew evolve` (main class `inflation.learning.EvolveKt`) improves the
wolves' network by evolution strategies; see Learned behaviour.

`viewer` opens the map with time controls (play, pause, step, one year ahead, speed), layers
(terrain with live rivers, floods and snow; live vegetation, temperature, rain, soil moisture and
river flow; ruggedness, travel effort, visibility, view from each place, defensibility; grazing, plankton, insects, rodents and soil life; potential biomes; rock; sediment),
herds and packs drawn on the map, a cell inspector, an event log, and tools to build
dams, dig canals, irrigate and clear vegetation. Scroll to zoom, drag to pan.

![viewer](docs/img/viewer.png)

## Known simplifications

- Rivers use single-direction (D8) routing, randomised during erosion to avoid parallel channels.
- Within a 6-day step, water reaches the river mouth at once, so flood peaks on large rivers come
  early and sharp. Daily temperature extremes are averaged away, which understates lowland snow days.
- Moisture comes from the west and a uniform background; there is no monsoon or explicit storm track.
- The archipelago limits catchment size, so the largest rivers carry a few hundred m³/s, not thousands.
- Vegetation starts from the long-term climate and takes decades of simulated time to settle;
  forests advance only from neighbouring forest.
- Volcanic cones are idealised shapes with noise; lava flows are not modelled, only ash.
- Animal groups move in one of eight directions a day and do not follow paths, so trails form
  only where traffic converges (around water). Trampling scales with the mass of animals passing.
- The herbivores' food supply ("palatable growth" and standing dead fodder, fixed shares of
  standing herbs and shrubs) is a calibration constant, not derived from plant physiology.
- Territories are not mapped; spacing comes from avoiding scent and from confrontations within a
  set distance per species. Predator numbers follow their prey only loosely.
- The predator guild was balanced by hand over a night of runs: all species persist, but lions
  stay rare (a few prides) on 400 km islands and the small deer are kept low.
- Seasonal migrations are not scripted; marches arise only from local shortage. Whether longer
  migrations emerge needs a larger map and learned behaviour.
- Visibility ignores weather, haze and night for now; the line-of-sight model sees only relief the
  1 km grid resolves, and the finer broken ground enters only through ruggedness.
- The moon's cycle is exactly 30 days to keep months and moons aligned (the real one is 29.53).
