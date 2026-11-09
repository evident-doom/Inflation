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

Not individuals (Olli, 9 Nov 2026). The simulation moves away from the identities of people towards the identities of
groups, so giving beasts identities would not fit. What matters is how the hunters move, are displaced and hold ground.

## Movement, displacement and territory (Olli's questions, 9 Nov 2026)

### Displaced by people

- **Now:**
  - Fear of hunters puts only plant-eaters off a place (`HexFauna.shy`: 0 for hunters). A resident field's choice of place
    (`appeal`) weighs food, ground, water, weather, snow, fire, sickness, crowding and stronger rivals, not people.
  - Fields spill to the next hex only from want (hunger, thirst, cold, snow); the settled wolves and lions from want alone.
  - A pack holding ground leaves it only when restless.
  - So people who hunt predators thin them where they hunt, but never push them away.
- **Is it needed:** at forager densities hardly. With herds and villages, yes. Large carnivores keep out of people's way
  where they are hunted (a "landscape of fear"), and persecution empties the country round settlements more than it
  kills outright. Hunters would come back by dispersal where pressure eases. That is also the route by which herding
  changes the predators' map.
- **How, cheaply:** one **pressure field per hunting kind**, kept by hex:
  - It is made of the people about (their heads within a day's walk) and of that kind's deaths at people's hands
    lately. It fades over months, at a rate a day, so it is the same at any step length.
  - Fields read it as they read fear: in `appeal` (`1 - FEAR_WEIGHT * pressure`), as a want that makes them spill, and as
    less room when dispersers settle.
  - Packs read it as restlessness: past what the kind bears, they leave their ground.
  - Cost: one more array per kind, updated once a step.

### Following prey through the seasons

- **Now:**
  - Resident hunters spill toward better hunting each step, and shift between a hex's low and high ground with the
    season (`shiftBands`). So they follow game up and down the mountains and across a few hexes.
  - The nine migratory plant-eaters (`Catalogue.migratory`: gazelle, wild goat, horse, onager, oryx, ostrich, hartebeest,
    dromedary, argali) move as fields along their sense of better country.
  - The dispersal pool stays at the hex it left until it settles or dies, so it follows nothing.
- **Real patterns:**
  - Most of these predators hold territory the year round and do not follow long migrations.
  - What follows the herds is the nomads: lion males without a pride, and dispersing wolves. In the steppe and desert,
    rain-driven grazers (onager, wild horse, oryx, ostrich) are followed by whatever is not tied to a den.
  - Altitude moves follow wild goat, ibex and argali within a territory, which the low/high shift covers.
- **How:** the dispersal pool becomes a **moving field**. Each step it drifts toward better hunting, by the same `worth`
  and bearings the fields' spill uses, and it settles where it finds room. So nomads follow the migratory grazers and
  colonise empty ground, at field cost and the same at any step length. Wolves and lions then have both behaviours
  without packs:
  - a settled field that holds territory;
  - a roaming field that follows the game.

### Territory wider than a hex

- **Now:**
  - A field's territory is implicit: its `usual` density (about one pack in 3-7 hexes) and the strife past it. Its
    hunting is spread over the places by density, which suits ranges wider than a hex.
  - A pack has a home hex and a radius (`territoryKm`: wolf 12 km, lion 14, leopard 11, cheetah 13). Its ground is "how
    far from home". Its hunting ground is whatever hexes its path went through that step, so it changes with the
    step's length.
  - Real ranges are wider in poor country: wolves 100-300 km² in the Mediterranean, leopards in arid land 100-400 km²,
    cheetah females far more. They scale with prey.
- **How:** a settled pack's **territory is a footprint, not a position:**
  - The hexes within its radius by least effort over the map (`effortsFrom`, as neighbours' contact), each weighted by
    nearness, laid out once a territory and kept.
  - The radius scales with the prey there, as `territory` already scales a pack's ground with game. It is re-laid only
    when it changes much.
  - The pack hunts its footprint by the weights, marks it (scent on every hex of it, by weight), dens at home, and meets
    whatever overlaps its footprint at rates, as bands meet neighbours.
  - Two packs' footprints overlapping are rivals. Who gives way is settled by the packs' meeting rules as now.
- **What this fixes:**
  - Territories several hexes wide come out right at any step length.
  - The planner is gone for settled packs.
  - Fewer timetabled stays.

### Similar items

- **Herds and retaliation.** Kept herds draw predators to camps (`herdOdds`, `raidHerd`). A band that loses stock could
  hunt the hunter about: that kind's pressure rises round the camp. This is the conflict that pushes predators back
  from herding country.
- **Camp followers.** Bold jackals and wolves come to camps (`followsPeople`, `bold`). Pressure and boldness pull against
  each other per kind: fear of people (`fearOfPeople`) sets the balance.
- **Stronger rivals.** Fields avoid places where a much stronger hunter holds sway (`outranked`), packs do too
  (`topStrength`). With footprints this becomes overlap with a stronger kind's footprints and fields.
- **Uninhabitable hexes** (fire, drought, deep snow) empty fields by want and push packs into restlessness, as now.

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

## Order, revised with the movement questions (9 Nov 2026)

The steps above, reordered so that each also does what the movement section asks. Savings are wall ms a step on the
larger world (208 ms), estimated from the recording.

1. **Step 1a**, the pool's kinds on two cores. Results unchanged. Saves 20-25 ms.
2. **The pool as a moving field** (the nomads following the game), done with **step 1b** (sources at once). The pool's
   step is redone either way, so both go in together. Results change; A/B against step 1a. Saves 45-50 ms in all with
   1a.
3. **A pressure field per hunting kind**, read by fields, dispersers and packs. A model change, not a saving.
   - A/B on a test world with herders (`t.startHerders`), where it should show.
   - Costs about nothing.
4. **Territory footprints for settled packs**, which takes in steps 2 and 3:
   - no planner for content packs;
   - hunting and scent over the footprint;
   - meetings by footprint overlap.

   Results change; A/B, and the hunters' network retrained on restless weeks only. Saves 10-15 ms (meetings, choosing)
   now, more on the full map.
5. **Step 4**, the sense of direction only where someone roams. Saves about 5 ms.

Together, roughly 208 ms to 130 ms a step, with the displacement, nomads and multi-hex territories the movement questions
asked for. Each step is measured before and after.
