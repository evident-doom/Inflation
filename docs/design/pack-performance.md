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

## Measured: step 2, the pool at once and drifting (9 Nov 2026)

Behind `t.poolDrifts` (off by default). `disperseAtOnce` weighs every source against the room as the step found it, each
hex then takes as many as it has room for (each source's settlers cut by the same share), all in parallel loops with
fixed-order sums (the same each run). `drift` then moves those still dispersing a hop a tick toward hexes worth more to
the kind (the best of a hex's places' `worth`), as the plant-eaters flow: the share `DRIFT_STEP` of the best gap, at
most a half, eased by the border and the kind's bearings. Fewer than 0.005 heads waiting on a hex die out, and those
waiting under `DISPERSAL_LEAST` now die on the way too, which they did not before.

Seed 1, 3 years, bands staying, against the pool as it was (step 1a). The census has a new line, "dispersing": the
pool's heads, its hexes, and the worth of its hexes against the settled's.

| | wolves settled, year 3 | lions settled | pool: wolves / lions | pool's worth / the settled's |
|---|---|---|---|---|
| 400x600, 6 days, before | 1168 | 766 | (not counted) | |
| 400x600, 6 days, drifting | 1144 | 730 | 59 / 6 | 1.22 / 1.50 |
| 400x600, 60 days, before | 1257 | 768 | 49 / 25 | 0.99 / 0.93 |
| 400x600, 60 days, drifting | 1254 | 753 | 50 / 22 | 1.28 / 1.41 |
| 800x1200, 6 days, before | 9698 | 6566 | | |
| 800x1200, 6 days, drifting | 9651 | 6289 | 488 / 98 | 1.28 / 1.51 |

- **The nomads follow the game:** the pool sits on hexes worth 1.2-1.5 times the settled's to the kind, against about
  1.0 (as where they were born) before. About the same at 6 and 60 days.
- **Numbers:** wolves within 2%, lions 2-5% fewer: more of them wait in the pool where the hunting is good but the
  room is taken, and die on the way. A first value for `DRIFT_STEP`; nothing tuned.
- **Time, larger world:** the pool 20.7 ms a step to 8.6, groups' births and deaths 41.4 to 31.3; the whole step
  217.5 to 213.0 (within the machine's noise). Less than the 45-50 the plan hoped for. What is left is mostly a dozen
  short parallel loops of about 1 ms each, over every hex for both kinds, and the ground of hexes the drift newly
  brings dispersers to (1.8 ms: the cache of grounds now grows over more of the map, up to about 5 KB a hex for wolves).
  Folding the hex loops together and keeping only the pool's hexes in a list would take most of that away.

### Trimmed (9 Nov 2026)

The pool's seven passes over every hex fold into one pass on the kind's own core (room, sources, those waiting and
their dead). The drift works out worth and ways only for the hexes the pool is on, and each hop gathers only on those
and the hexes next to them. The ground about a new source is walked without boxing a hex. The arithmetic and its order
are the same: the drifting runs come out line for line as before (400x600 at 6 and 60 days, and 800x1200 at 6 days:
0 of 377 lines differ).

Larger world: the pool 8.6 ms a step to 6.7 (20.7 before step 2). What is left is the five parallel loops that do the
work (sources, the ground of new sources, wanted, placed, room taken), about 1.1-1.4 ms each. The whole step read 224 ms
this run against 213 before, with meetings and others slower too: the machine's noise is larger than the saving.

## Measured: step 3, people's pressure on the hunters (9 Nov 2026)

Behind `t.huntersPressed` (off by default; off, the runs come out as before, one more table counted in the limits).
`HuntersPressure.kt`:

- **The field:** for each resident hunter kind and place, the people within a day's walk (each band's heads over its
  hex and the six about it, per km², against `PEOPLE_HALF` = 0.5) and the kind's persecution: its heads killed by people
  lately, plus half a head for each head of stock it took from a band (`RETALIATION`), as a share of its usual numbers
  against `KILLED_HALF` = 0.2, fading with e-folding 120 days. Each part saturates; the sum is scaled by the kind's
  `fearOfPeople` less the boldness of those there, so camp followers are not put off.
- **Readers:** a resident hunter's `appeal` (as fear for the game), its `need` to spill (so the settled leave pressed
  ground too), the dispersers' room on a hex, and a pack's restlessness past 0.25 (`pressedOff`, by the people about
  only: the packs' kinds keep no persecution).
- **Cost:** a pass over the bands once a step, one table per hunting kind. Nothing to see in the timings.

Seed 1, 400x600 km, 3 years, 6 days, bands staying, the pool drifting, half the bands herders (and in the second pair
half settled in villages):

| | wolves | lions | leopard packs | pressure where they live (wolf, lion; lynx) | land pressed past a quarter (lynx) |
|---|---|---|---|---|---|
| herders, without | 1170 | 729 | 43 | | |
| herders, with | 1148 | 734 | 39 | 0.02, 0.02; 0.04 | 4% |
| herders and villages, without | 1165 | 729 | 45 | | |
| herders and villages, with | 1143 | 733 | 43 | 0.02, 0.02; 0.04 | 5% |

At 60 days the herders' world gives the same (wolves 1244 / 1242, lions 754 / 754).

**Nothing presses the hunters on these worlds yet.** People are about 0.03 a km² (bands of 33, villages no bigger in
three years). Bands killed no beast of prey in the census's window, and beasts took no kept stock: 0 heads by every kind.
So the field sees only the people about, about a twentieth of what would move a hunter, and the differences above are
within what a small change anywhere does to these runs. The field is ready for denser people (farming villages, longer
runs, the full map). The test that it does what it should is a world where people are many and hunt or lose stock
to beasts: there the hunters should thin out around them and fill back in by dispersal as the pressure fades.

## Measured: step 4a, packs hold their ground (9 Nov 2026)

Behind `t.packsStay` (off by default), `PackGround.kt`. Only leopard and cheetah packs are left as packs (wolves and
lions live as fields and the dispersal pool), so this is about them.

- **The rule:** a pack on its home hex that is not restless (`restless`: fed, has ground, not thirsty, not astray, not
  pressed by people) and warned of no peril there weighs no place and stays (`holdsGround`, `groundPlan`). A pack away
  from its home hex, or restless, weighs its places as before. Nothing is learnt from the stays: the hunters' network
  still drives the restless weeks, as trained on all weeks (retraining on restless weeks only is left for later).
- **The footprint:** its home hex and every hex with ground within its range of home (`territoryKm` scaled by the game
  on its home ground, half to three times, as the planner reckons a pack's ground; `denKm` while denning), each weighted
  `1 - d / (range + a hex)`. Laid out each step when the pack stays (a few dozen hexes at most).
- **Hunting:** its step's search is shared over the footprint's places by weight, each as far as its area a tick goes,
  what a place cannot take shared again among the others (`footGround`). It no longer depends on how far the path ran
  that step. The foraging waves count the footprint and the six about each hex as the pack's ground, so two parties that
  could touch the same ground are still never in one wave.
- **Scent:** it marks its footprint, each hex as fresh as its weight, home 1. Outskirts weighted under `MARK_FRESH`
  (0.3) are marked faintly, and others of its kind may settle and hunt there: territories overlap at their edges.
- **Not yet:** meetings by footprint overlap. A staying pack's timetable is its home hex alone, so rivals meet it only
  when they come in.

Seed 1, 3 years, bands staying, the pool drifting:

| | packs that stayed without weighing (leopard, cheetah) | leopards | cheetahs | footprint hexes (mean, widest; leopard) |
|---|---|---|---|---|
| 400x600 km, 6 days, without | 0%, 0% (weighed and stayed 89%, 98-100%) | 119 | 2 | |
| 400x600 km, 6 days, with | 95%, 97% | 127 | 14 | 1.4, 7 |
| 400x600 km, 60 days, without | 0%, 0% (weighed and stayed 81%, 80%) | 22 | 4 | |
| 400x600 km, 60 days, with | 92%, 100% | 12 | 6 | 2.2, 7 |
| 800x1200 km, 6 days, without | 0%, 0% (weighed and stayed 93%, 94%) | 1043 | 68 | |
| 800x1200 km, 6 days, with | 97%, 100% | 926 | 76 | 1.5, 19 |

On the small world the counts are a few dozen packs and swing with any change. On the larger world leopards end 11%
fewer and cheetahs 12% more. Leopards are as well fed (0.99 against 1.00) and die of the same things. Most footprints
are a hex or two: leopard ground is rich, so their range is scaled down below a hex's width. The few wide ones are in
thin country.

Timing on the larger world (recordings, the machine equally loaded): choosing over all groups 5.6 to 3.8 ms, the
choosing phase 16.3 to 14.4 ms, meetings 24.2 to 23.2 ms, the whole step 197.6 to 190.0 ms. Less than the plan's 10-15
ms, because the content packs were cheap to weigh already (most of the choosing is the bands' and the place values).
The rest of the step's saving is in meetings by overlap rather than timetable (step 4b).

## Measured: step 4b, meetings over the footprint (9 Nov 2026)

Also behind `t.packsStay`. A pack that holds its ground is filed for the meetings on every hex of its footprint, the
whole step, each for its weight's share of the time (`Stay.share`, the weight over the footprint's sum). A meeting's
time together is the overlap of the two stays times both shares, so the encounters drawn from it scale with how much
of their time each spends there. Everything else is the meetings as they were:
- Rival packs whose footprints overlap meet in the overlap, as do enemies of other kinds and bands passing through.
- The resident hunters' stand-ins are made for the footprint's hexes too, so a leopard meets the lions and wolves of
  its whole territory, not only those of its home hex.
- Home advantage, and losing ground to a rival, still go by the home hex. A fight in the outskirts costs no ground.

This is the timetable machinery with a footprint in it, not meetings drawn from rates without timetables. It costs
rather than saves: more hexes hold stays and stand-ins.

Seed 1, 3 years, bands staying, the pool drifting; "with 4a" is packs staying, footprint hunting and scent only:

| | leopards (without / with 4a / with 4a and 4b) | cheetahs | leopards born / died, last year (with 4a and 4b) |
|---|---|---|---|
| 400x600 km, 6 days | 119 / 127 / 110 | 2 / 14 / 6 | 55 / 53 |
| 400x600 km, 60 days | 22 / 12 / 29 | 4 / 6 / 7 | 23 / 33 |
| 800x1200 km, 6 days | 1043 / 926 / 944 | 68 / 76 / 53 | 486 / 485 |

Without the switch the results are the same as before, line for line (6 days, small world). On the larger world
leopards stay about 10% under the run without; cheetahs swing between 53 and 76 among the three runs, a few dozen
packs. Deaths to rivals are 3% of leopard deaths against 2%, and killed by lions 7% against 6%: the overlap meetings
are there, a little more often than the timetables gave.

Timing on the larger world: meetings 23.2 to 26.0 ms, the whole step 190.0 to 202.0 ms (the second run under somewhat
less load from other processes, 12% against 16%). Meetings drawn by rate for groups that stand still (the plan's step 3)
remain the way to the saving.

## Measured: meetings on more cores, results unchanged (9 Nov 2026)

The plan's step 3 was meetings drawn by rate, not timetable, for groups that stand still. The recording of step 4b
showed that is not where the meetings' time goes. Of the phase's 26 ms on the larger world, most is serial work on the
resident hunters' stand-ins (`ResidentParties`: making them, listing them by hex, booking what their meetings among
themselves came to). The bands' neighbour meetings take about 4 ms, nearly all of it each band's least-effort search
over the hexes about it (`effortsFrom`). The timetable of groups that stand still is a few stays a group, and cheap.
Rates there would change the results for little. So instead, with the same results:

- **Neighbours' going:** each staying band's search, and the peopled hexes of it in index order, are worked out on all
  cores (`meetings: neighbours' going`). The draws then go band by band in the groups' order, as before.
- **Stand-ins by hex:** the hexes with two or more stand-ins are listed as indices, and each hex's list is made inside
  its own job in `meetings: residents by hex`, not all serially before it.
- **Smaller:** the meetings' stop array is filled for the groups only (a stand-in's stays 0), and the stand-ins' hex
  index is made big enough at once.

Identical results: the logs diff empty, apart from timing lines, against those before (small world on at 6 and 60
days, off at 6 days; the larger world on at 6 days).

Timing on the larger world: meetings 26.0 to 22.8 ms, with the machine more loaded by other processes in the second
run (27% against 12%), so the gain is if anything larger. The whole step read 202.0 and 201.1 ms under those loads. What
is left serial in meetings is mostly making the stand-ins (`ResidentParties.<init>`) and booking their meetings
(`standInsMeetBook`).
