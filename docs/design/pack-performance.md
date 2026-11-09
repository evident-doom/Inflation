# Packs: making them cheaper, keeping roaming and territory

Written 9 Nov 2026 on `main` at cd679a5b (variable step, `t.bandsStay`), after the bands' movement went.

## What lives how now

| Kind | How it lives | Moves as |
|---|---|---|
| Plant-eaters, jackal, lynx, striped hyena, brown bear | residents: numbers per place | the residents' spill and seasonal moves |
| Wolf, lion | residents, plus a roaming minority (`Catalogue.roamingResidents`, docs/design/roaming-packs.md) | settled: residents. Grown young leave into a **dispersal pool** (`HexFauna.disperse`, variable step) that settles where there is room. Packs only from the world's start. |
| Leopard, cheetah | **mobile packs** (`HexGroup`s) | the planner and the learned hunters' mover, every step |
| People | bands holding a home hex (`t.bandsStay`) | no planner unless a network drives them |

## Measured

`profile=jfr`, 6 days a step, 3 years (174 steps after warm-up), N3 nets, `t.bandsStay=true`, Olli's PC (16 cores). Two worlds, seed 1:

- **Small, 400×600 km:** about 45 bands, 43 leopard packs and 4 cheetah groups.
- **Larger, 800×1200 km:** about 470 bands, 380 leopard packs and 37 cheetah groups. No wolf or lion packs were left; they lived as fields and dispersers.

The larger world's step was 208 ms. Of that, what belongs to the hunters that range:

| Cost | Phase | ms a step | What it is |
|---|---|---|---|
| **Wolves' and lions' dispersal pool** | groups: births, deaths (62 ms, 98% on one core) | **~45-55 wall** | `disperse`: a hex at a time, one core, every source hex every step |
| Meetings | groups: meetings (20 ms, 87% on one core) | up to ~20 wall | timetabled stays of every group, with the resident stand-ins of every hex groups pass (`meetSettle`, `ResidentParties`) |
| Leopards' and cheetahs' choosing | choosing | 17 thread-ms (~2 wall) | 405 groups at 40-70 µs; 361 of 369 leopards hold ground |
| Leopards' and cheetahs' foraging | hunting and foraging | 12.8 thread-ms | 11% of foraging (bands are 88%) |
| Their sense of direction | residents: new month | 6.1 wall | a month's bearings for every pack kind over every hex |
| Their life and death | | 2.1 thread-ms | |

On the small world the pool was 45 of the step's 83 ms before today's two fixes (its ground worked out once per hex and kept, reused arrays). Those fixes leave the results line for line the same, but the pool still runs one hex at a time on one core.

Cost of a resident field for comparison (roaming-packs.md, m5-1): about 75 thread-ms a step whatever the numbers, because it walks every land hex. Turning leopards into a field would not be cheaper.

## What has to be kept

- **Territory:** packs that hold ground keep to it while fed, keep off ground other packs of their kind have marked fresh (scent), den with young, and fight rivals.
- **Roaming:** packs without ground, the hungry, the thirsty and those far out of their country go further, following their kind's sense of better country. Young wolves and lions disperse and settle where there is room.
- **Meeting:** packs meet other hunters, bands and residents, and are game for stronger hunters.
- **The learned hunters' mover** keeps choices to learn from.

## The plan

Each step below is behind its own switch until A/B'd at 6 and 60 days, as `t.bandsStay` was.

### 1. The dispersal pool on all cores (the largest saving)

`disperse` goes source hex by source hex, and each settling takes room the next source sees. Two ways to make it parallel:

- **a. Kinds apart (results the same):** wolves and lions have their own fields, rooms and pools. Run the two kinds at once, with the carcasses each leaves gathered apart and added after. That halves the pool's time at most.
- **b. Sources at once (results change slightly):** every source weighs the room as the step found it. Then each destination hex's settlers are scaled to its room where more came than it holds. Both passes are parallel, over sources and then over destinations. No first-come order between sources, which is arguably truer to the step being one stretch of time. Expected: ~50 ms to a few ms on the larger world.
- **Also:** a source hex keeps a pool down to 0.05 heads (`DISPERSAL_LEAST`) and is weighed every step while it does. Settling or losing the last tenth of a head at once would shrink the list of sources. The number of sources still has to be counted.

### 2. Settled packs keep to their ground without the planner

The same route the bands took. A pack that holds ground and is not restless (`Lifeways.restless`: fed, has ground, not thirsty, not astray) and not under threat stays on its home hex. It patrols its territory, which is about a hex across for leopards (`territoryKm`), hunts its ground, marks it and dens, with no reach search.

Only restless packs run the planner as now: the homeless, the hungry, the thirsty and the astray, with their reach growing with the step (`stepShift`). The roaming behaviour stays where it is and costs only for the few that roam.

- **The choice still learned:** whether a pack leaves its ground is what the gate and the mover learnt. The rule above takes over the "stay" side for the content, which they chose 84-88% of weeks (roaming-packs.md). The hunters' network then learns only from restless weeks: fewer samples, all of them real choices.
- **Meetings:** a settled pack's way is its home hex, so it meets what comes into it. Its rivals on the next ground are met as bands meet neighbours (step 3), by overlap of their territories, not by a timetable.
- **Expected:** packs' choosing ~17 thread-ms to ~2 (only restless weeks), and less wear and fewer timetabled stays (step 3).

### 3. Meetings by rate, not timetable, where nothing moves

With bands and settled packs each on one hex, the timetable machinery (`staysByHex`, the stays and their days, `ResidentParties` for every hex a group passes) mostly lays out groups that stand still.

- For groups that did not move, a hex's meetings can be drawn straight from encounter rates over the step's days (`encounters`, as now, with the span the whole step).
- The resident stand-ins would be made only for hexes where a group stands, and on all cores: `ResidentParties.<init>` and `standInsByHex` are the top serial frames now.
- Roaming groups keep their timetables.
- **Expected:** meetings 20 ms to a few, mostly parallel.

### 4. The sense of direction only where someone roams

Bearings are worked out monthly for every pack kind over every hex (6 ms a step on the larger world), but only restless groups follow them.

- Work them out for a kind only in months when some group of it is restless, and only over the hexes within its reach of such groups. Or keep a coarser field (every third hex) for settled months.
- **Expected:** 6 ms to under 1.

### 5. Then, with less to gain

- **Packs' foraging:** 12.8 thread-ms, already parallel. A settled pack's hunting ground is its territory, which could be laid out once and kept (the same once-per-map idea as the dispersal ground).
- **Wolf and lion packs from the world's start:** they could start as roamers in the pool, so that no wolf or lion group is ever made. One code path the fewer.

## Order and what it should give on the larger world (208 ms a step)

1. Step 1a, results unchanged: −20 to −25 ms.
2. Step 1b, results change, A/B: down to a few ms for the pool, −45 to −50 ms in all with 1a.
3. Step 4: −5 ms.
4. Step 2, results change, A/B, and the hunters' network retrained on restless weeks only: −2 ms wall now, more on the full map, where packs are many.
5. Step 3: −10 to −15 ms.

Together, roughly 208 ms to 130 ms a step on the larger world, most of it from the dispersal pool. These are estimates from the recording. Each step is measured before and after.
