# One weekly clock: groups as roaming ranges

Proposal, 2026-10-12. Nothing here is built.

## Why

A world step is 6 days. The animal layer then runs each of those 6 days, so every group perceives, walks, eats and drinks six times per step. That daily loop is about 90% of a step: 714 of 780 ms on the full map, measured on the 16-thread desktop.

## The clock

Keep a single step of 6 days, the current one, so that 5 steps make a 30-day month and a moon. That is the "week". Every system, animals included, acts once per step. Nothing runs daily.

## A group is a range, not a point

A group is:
- a **centre** (x, y),
- a **range radius**, km: the area it roams in a week. It is set by how much ground it needs for a week's food at the forage density there, and bounded by its daily travel times 6. In rich ground it is small, in poor ground large.
- **state**: size, condition, thirst, alert, stored meat, march heading.

Its presence is a probability cloud over the range, for now a disc with weight falling towards the edge. The position on any given day is not defined until something needs it (see "Collapse" below).

## Coarse layers instead of walking cells

The physical world stays on the 1 km grid. The ranges are too big and too fuzzy for 1 km detail to matter, so each step builds a few **summed-area tables** from the 1 km layers: food per diet class, water access, cover, ruggedness, wetness, snow, and the travel cost of crossing. The total or mean over any box is then 4 lookups, whatever its size. That is the main speed gain: a candidate range costs a few dozen lookups, where today a day's options cost hundreds of cell steps and neighbour scans.

## The weekly decision

Each group scores candidate ranges, the way it scores today's 9 options:
- **Candidates:** stay; 8 directions at 1, 2 and 3 times its normal weekly drift; and on a march 8 directions at its marching reach. That is about 33 candidates.
- **Features per candidate:** food for its diet within the range, share of the range within reach of water, mean cover, ruggedness and wetness against its preferences, the danger field, kin crowding, and the travel cost to get there (from the travel-cost table along the line, plus a penalty for crossing water).
- **Choice:** the hand-written baseline or the network picks one, exactly as now. The same network shape works; there are just more options, and decisions are 6 times rarer, which also makes training cheaper.

"Unless surprised, attempts to stay in advantageous terrain" becomes the preference terms plus inertia: moving the centre costs condition, so a group stays where it does well.

## Eating and competition

Each step, every group sends its week's demand to the coarse cells under its range, weighted by its preference for each. A first pass sums demand per cell. A second pass shares each cell's stock among the groups in proportion to their demand (with a small edge for the dominant species if wanted). This is order-free, so all groups can run in parallel, and overlapping herds compete without being processed one by one.

Water works the same way: the share of the range within reach of water decides how much drinking costs that week.

## Interactions as overlap

Encounters between groups are expected rates from how much their ranges overlap, not exact meetings:
- **Predator and prey:** expected encounters in a week = overlap of the two clouds × how fast they move × how far they are noticed (visibility, cover). Hunts are drawn as a Poisson number of encounters, capped by how much the predator still needs, and each succeeds as now (young and weak, defence, speed, stalking and cover, fight-back).
- **Surprise:** whether the prey notices first follows from cover against the predator's stalking. A surprised herd loses more and shifts its centre afterwards; one that noticed first pays only a flight cost.
- **Packs and rivals:** territorial meetings, intraguild killing and kill theft follow from overlap rates the same way. Territory emerges as packs avoid the occupancy of their own kind.
- **Scent and danger fields:** the scent grids are replaced by an occupancy field per species (each range spread over the coarse grid), built once per step. "Danger at a candidate" is the threat-weighted occupancy of the predators that can kill the group.

## Fast species and grid size

- **Keep the 1 km grid** for terrain, water and plants. Enlarging it would cost rivers, valleys and line of sight.
- **Movement scale:** a group moves its centre up to its weekly reach, which is about 100 km for an onager on a march. The decision needs only the coarse tables and the cost of the straight line. For long marches over awkward ground, a cheap shortest path on a 4–8 km grid can replace the straight line.
- **Migration** fits naturally: a march heading persists over several weeks, and remembered places (from the learned-agents plan) become candidates.

## Collapse: when a position is needed

Some things need a point: a human group meeting a herd, a hunt event to show, a trail, the viewer. For these, draw a position from the cloud, weighted by the group's preference within it, at that moment. The draw can be seeded by step and group id, so a run stays reproducible.

## What changes and what does not

| Stays | Changes |
| --- | --- |
| Species data, demography (already per step), carrion, fish, plants, the physical world | Group movement and feeding (range model), hunting and encounters (overlap rates), scent (occupancy fields), trampling (spread over the range, heavier at the centre and along marches), the viewer (draws ranges as discs) |

## Rough cost (estimate, not measured)

- **Today:** 6 days × groups × (9 walks of 5–15 cells + neighbour scans).
- **Proposed:** 1 × groups × (33 candidates × about 20 table lookups), plus building the tables once per step in parallel over the grid.

I would expect the animal layer to get 5 to 10 times faster. The real figure comes from building it.

## Risks

- **Calibration** was done against the daily model and will need redoing, especially kill rates and competition.
- **Overlap rates** need a constant for how often groups that share ground actually meet. That is new and has to be calibrated: there is no "they walked into each other" anymore.
- **Local depletion** within a range is averaged away, which helps grazers that would otherwise eat a single cell bare. Patchiness can come back through the preference weighting.

## Suggested order

1. Summed-area tables and the occupancy fields, with the current daily model running on them, to check numbers before anything else changes.
2. Ranges and the weekly decision, with feeding by proportional sharing; remove the daily loop.
3. Encounters by overlap: hunts, territory, theft, intraguild killing.
4. Recalibrate on the four test islands; time the full map.
5. Re-copy the networks, then resume evolution on the faster model.
