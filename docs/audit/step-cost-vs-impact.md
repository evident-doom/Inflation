# Where a step's time goes, against what each part does

Measured 2026-10-14 on a 10-year run (seed 1, 1000 × 2000 km, commit after 0bf26fb). The run uses `-Dhex.profile=fine` on Olli's 16-thread PC. The step is 132 ms.

Two measures are used:
- **Wall time (ms per step):** what the step waits for.
- **Thread time:** the summed time of every core spent in a part. It shows the parts that run on all cores at once.

At the end of the run there were about 345,000 migratory herd groups, 12,000 hunting parties and 750 bands.

## Ranked: most time, least effect

| # | Part | Wall ms/step | Thread time | What it does | Effect on the world |
|---|---|---|---|---|---|
| 1 | **Herds choosing where to go**: least-effort search over the reachable hexes (283 thread-ms), and scoring every candidate place (210 thread-ms) | ~40 of 44 | **73%** | Each of ~345,000 herd groups picks next week's place. Content groups skip every other week; still 75–95% of herd groups weigh their places each week. | **Migration** (Jan→Jul 40–80 km), where herds graze and drink. But herds are **~2.5% of all kills**: about 100 of ~4,000 a week; the rest are residents. |
| 2 | **Meetings: finding who meets whom** (timetable overlaps) | ~13 of 15 | (1.5 cores) | Scans every group's hexes and days for overlaps. Settling the meetings themselves is cheap (~2.5 thread-ms). | Hunter–herd meetings (1,338 a week) give the herd kills above; packs fighting (1,056) and giving way (451); **band meetings (435): culture, marriage, telling, feasts, ties**. |
| 3 | **Water life: plankton, fish, shellfish** (each hex, a daily loop inside the week) | 6 of 7.5 | **11%** | Nutrients, phytoplankton and zooplankton day by day, then fish and shellfish. | Feeds sea fish (~1,800 t grown a week), and through them dolphins, porpoises, seals, sea birds and people's coastal fishing. Nothing on land depends on it except coastal bands. |
| 4 | **Hunting and foraging** (single-threaded) | 19.6 | 3% | Every hunting party's and band's optimal diet. | **High:** all hunters' and people's food, almost all kills of residents. |
| 5 | **Residents moving** (up and down slopes; spreading to neighbouring hexes) | 8.7 | 1.3% | Resident herd densities shift between height bands and hexes. | **High:** residents are the bulk of grazing and of prey. |
| 6 | **Groups' life and death** (births, deaths, splitting, merging) | 9.0 | 3% | Each group's week. | **High.** |
| 7 | **Small life** (soil, insects, rodents, reptiles, game, foxes, owls, eagles, vultures, locusts, spreading) | 6.7 | 3.5% | Soil and small animals per place. | **Medium:** jackals, foxes and owls live on it, and it is 1–26% of hunters' food. Soil feeds the plants. |
| 8 | **Sense of direction** (bearings, monthly) | 4.7 | small | Where better country lies for each kind. | **Medium:** it guides herds and residents out of unsuitable land. |
| 9 | **Residents' grazing, births and deaths** | 7.0 | 2% | Per kind per place. | **High.** |
| 10 | **Water and plants, weather, food stocks** | 2.8 | small | Soil water, plant growth, fire; the week's weather. | **High**, and cheap. |

Parts under 0.5% of thread time: outbreaks, locusts, vultures, sea birds, spawning runs, visiting kin, burning off, marking ground.

## What this means

- **The migratory herds as agents cost about three quarters of all computing**, and almost all of it is their choosing. They matter for migration and for the movement learning. They barely matter as prey: hunters eat residents.
- **The meeting scan** is the next biggest wall-time item because it runs on one core. Most of what it scans is herd timetables, and most herd meetings lead to nothing.
- **The plankton day-loop** is a clear simplification candidate: it is 11% of thread time for a sea that feeds only sea animals and coastal bands.

## Options to consider (nothing done yet)

1. **Herd groups as bigger units** (e.g. 10× the head count per group): about 10× fewer agents and about 10× less choosing and scanning. Migration stays, and learning still sees decisions, just fewer of them. A herd of 200 gazelle splits into feeding parties in reality, but they move together.
2. **Herds choose less often:** only when restless, or every 2–4 weeks when content. This is cheap, and it matches how herds really behave (they stay while the grazing holds).
3. **A cheaper reach search for herds:** search fewer rings, or reuse last week's reach while the herd hasn't moved.
4. **Meetings scan only pairs that can matter:** hunter with herd, hunter with hunter, band with anyone. Never herd with herd. Spread the scan over cores.
5. **Plankton by the week, not by the day:** the same balance on a coarser clock, or a closed form, for about 5× less.
6. **Hunting and foraging on all cores:** split by region, with stocks claimed per region. This is wall-time only.

Options 1–3 attack the 73%. Option 4 attacks the next biggest wall-time item. Option 5 removes most of the sea's cost.
