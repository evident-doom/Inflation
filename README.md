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
through a `Policy`: a hand-written baseline now, learned policies later, from the same inputs.

| Layer | Model |
| --- | --- |
| Plankton | Nutrients, phytoplankton and zooplankton in sea and lake cells, stepped daily. Nutrients are rich on shallow shelves and at river mouths, poor over deep water; cold water mixes nutrients up, warm water stratifies. Gives a spring bloom and coasts far richer than open sea |
| Soil life | Plant litter from turnover, die-back, frost, floods and ash decomposes at a rate set by warmth and moisture; part becomes soil organic matter, which raises the fertility plants grow on. Carcasses rot in days to weeks and feed scavengers |
| Small animals | Plant-eating insects (active above 10 °C), biting insects (in warm wetlands and floodwater) and rodents (logistic on food, hit by cold and snow), in kg/km², spreading to neighbouring land |
| Gazelle | Herds of about 12 that split above 40. Each day a herd walks as far as a day's effort takes it over the terrain and weighs forage, water, snow, crowding, cover (it keeps to open ground) and the scent of wolves, or the sight of them where cover allows. They eat palatable young growth, which regrows over a month, and drink. Births in spring, scaled by body condition |
| Wolf | Southern-type wolves (22 kg) in packs of about 3. Packs follow prey scent, chase herds within 4 km (young and weak gazelles are much easier to catch, big herds spot them sooner, and stalking in cover works better), and fill gaps with rodents and carrion. Packs that meet spend the day in confrontation instead of hunting and sometimes lose a wolf, so crowded packs feed worse |

Body mass sets food needs (Kleiber scaling) and starting densities (Damuth's rule). The species
figures are mid-range literature values recalled from memory, not yet checked against sources.

On a 400 km test island (seed 7), gazelles and wolves coexist over 25 simulated years. Gazelles
settle at roughly 40,000 to 60,000 (about 2 per km² of land), held below their food supply by
wolves. Wolves dip to about 30 and recover to about 90-100, roughly one per 260 km².

![animals](docs/img/fauna.png)
![visibility](docs/img/visibility.png)

The animal layer makes a full-size step about five times slower: roughly 0.5 s instead of 0.1 s.

Measured on seed 7 (`summary.txt`): no lowland below 300 m keeps snow lying even at 40°N. Land at
300–1000 m has it about 10 days a year in the north, land at 1000–2000 m 80–120 days, and mountains
above 2000 m most of the year.

![relief](docs/img/relief-seed7.png)
![biomes](docs/img/biomes-seed7.png)
![rivers](docs/img/rivers-seed7.png)

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
- The gazelle's food supply ("palatable growth", a fixed share of standing herbs and shrubs) is a
  calibration constant, not derived from plant physiology.
- Wolves only hunt gazelles; other large prey come with later layers. Pack territories are not
  modelled as such; spacing comes from avoiding other packs' scent and from confrontations.
- Visibility ignores weather, haze and night for now; the line-of-sight model sees only relief the
  1 km grid resolves, and the finer broken ground enters only through ruggedness.
- The moon's cycle is exactly 30 days to keep months and moons aligned (the real one is 29.53).
