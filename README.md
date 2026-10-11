# Inflation

A simulation of how societies, power, debt and money arise, built up from late stone-age groups.
The design brief lives in the project's Claude Doc. This repository starts with the physical world.

## Step 1: the physical world

`inflation.world` generates a 1000 × 1000 km region on a 1 km grid (one million cells), spanning
roughly 40°N to 31°N with open sea to the west: temperate in the north, hot and dry in the south.

| Layer | Model |
| --- | --- |
| Tectonics | 10 plates on a warped Voronoi map; collisions raise mountain belts, divergence opens rifts |
| Erosion | Stream-power law, implicit Braun & Willett (2013) solver, 40 steps × 50,000 years, plus hillslope diffusion |
| Geology | Rock type per cell (sets erodibility and soil); flint, clay, obsidian, ochre, salt, copper, tin, bitumen |
| Climate | Monthly normals: latitude, 6.5 °C/km lapse rate, continentality; westerly moisture with orographic rain and rain shadows; Mediterranean dry summers in the south |
| Water | Thornthwaite evapotranspiration, Fu–Budyko runoff, D8 routing; lakes from a water balance, closed basins turn saline |
| Weather | Coarse autoregressive temperature and rain anomalies, wet and drought years, snowpack with degree-day melt |
| Calendar | 12 months × 30 days; the moon is new on day 1 and full on day 16; the year starts at the spring equinox; day length by latitude |
| Biomes, soil | Classified from aridity, temperature and river flow; soil fertility from rock, slope and moisture |

One simulation step is 6 days (5 steps per month), configurable in `WorldConfig`.

![relief](docs/img/relief-seed7.png)
![biomes](docs/img/biomes-seed7.png)

## Running

Requires JDK 21.

```
./gradlew test
./gradlew run --args="--seed 7 --out output/seed7"
```

Generation takes about 20 seconds and writes PNG maps (relief, biomes, geology, minerals,
precipitation, temperature, soil) and `summary.txt`.

## Known simplifications

- Rivers use single-direction (D8) routing, randomised during erosion to avoid parallel channels.
- Lakes come from young deformation and a water balance; there are no glaciers or glacial lakes.
- Moisture comes from the west and a uniform background; there is no monsoon or explicit storm track.
- The moon's cycle is exactly 30 days to keep months and moons aligned (the real one is 29.53).
