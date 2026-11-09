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

## Contacts and engagements: one way groups come on each other (9 Nov 2026)

Olli, 9 Nov 2026: this work is the pattern for future groups of people that move (bandits, war parties, scouts). What
matters most is a model that reliably, predictably and reproducibly finds and settles the cases where two possibly
hostile groups' spheres of influence overlap, more than model changes or small performance gains. Strategies are set
beforehand and per hex: a band may defend its lair and yield to a stronger band on its fringe; a defender may draw the
other in, keep high ground, fall back to ground easier to hold, or hide and scout, ambush, attack when the other is
outnumbered. And the encounter logic must be the same whether or not the encounter turns out hostile.

### Built (step 4c, behind `t.packsStay`)

- **Contacts** (`Contacts.kt`): each pair of groups of which one holds its ground and whose grounds share a hex is one
  contact. A contact has:
  - the overlap: the sum over shared hexes of the product of the two groups' shares there;
  - the encounters to expect: each shared hex's rate a day times the days both are there, summed;
  - the hex where the overlap is thickest, and the contested hexes.

  They are found pair by pair on all cores, keyed and ordered by the groups' ids, so the same on any cores and in any
  order of the groups. One draw per pair, at most one meeting a tick. Cutting a hex in two leaves the encounters as they
  were (each hex's rate goes by its area). Tested in `ContactsTest`.
- **Yielding** (`gaveWay`): a pack holding its ground that gives way, or loses, keeps off the contested hexes for
  `YIELD_DAYS` (30): it neither hunts nor marks them, nor is it about them for the next contacts. Its home it loses only
  to a rival that beats it there. A group on its way turns back, as before.
- **Claims** (`Scent.claim`): two claimants a hex by kind, the fresher first. A claim is lost only as it fades or to a
  fresher third, not to whichever pack marks first.
- **Engagements** (`Engagement.kt`): a contact is settled by the two sides' strategies.
  - A strategy is a stance for each part of a group's ground: lair (home hex), core (footprint weight `CORE_WEIGHT` 0.5
    or more), fringe, outside.
  - Stances: `DEFEND`, `GIVE_WAY_TO_STRONGER` (the animals' rule), `YIELD`, `AMBUSH`, `HIDE_AND_SCOUT`, `ATTACK`.
  - In order:
    1. **Ground:** a side that would defend some contested hexes draws the meeting to the one it holds best: high
       ground over the other (up to `ENGAGE_HIGH` 0.5 stronger at 200 m above), cover (`ENGAGE_COVER`), its lair
       (`HOME_ADVANTAGE`). Otherwise the meeting is where the overlap is thickest.
    2. **Detection:** each may see the other first, by senses; one keeping out of sight is up to `ENGAGE_HIDING` (3) times
       harder to see in full cover.
    3. **Choice:** each acts on its stance by what it knows; of an unseen other, nothing.
    4. **Outcome:** one that saw the other coming may withdraw (it gives way); one taken unawares cannot, and its
       attacker strikes `ENGAGE_SURPRISE` (2) times stronger. Fights are the meetings' own (`rivalsFight`, `enemiesClash`).
  - Packs keep the animals' rule unless `t.packStrategy` sets one for test. A contact where both keep it is settled
    as any meeting is, so the strategies change nothing where nobody has one.

### Measured (step 4c)

Seed 1, 3 years, bands staying, the pool drifting, packs staying:

| | leopards | cheetahs |
|---|---|---|
| 400x600 km, 6 days, step 4b | 110 | 6 |
| 400x600 km, 6 days, contacts, the animals' rule | 100 | 7 |
| 400x600 km, 6 days, lair DEFEND, core GIVE_WAY_TO_STRONGER, fringe and outside YIELD | 130 | 10 |
| 400x600 km, 6 days, AMBUSH everywhere | 122 | 6 |
| 400x600 km, 6 days, ATTACK everywhere | 110 | 9 |
| 400x600 km, 60 days, step 4b / contacts | 29 / 23 | 7 / 6 |
| 800x1200 km, 6 days, step 4b / contacts | 944 / 915 | 53 / 65 |

- Without the switch the results are the same as before, line for line.
- The census weeks show the engagements at work under the test strategies: ambushes, packs watching each other unseen,
  passing unseen, giving way; packs holding ground giving up 1 to 38 hexes in a census week. Under the animals' rule
  no contact goes by strategy, as meant.
- The numbers move within what any change does to these few dozen packs. On the larger world the leopards are 3%
  under step 4b.
- Timing on the larger world: meetings 22.8 to 23.0 ms, the whole step 201.1 to 201.8 ms. Contacts cost about what
  the meetings over the footprint did.

### Next: every encounter through the same steps

Today a meeting comes about in seven ways, and is settled in five. Each way has its own idea of who meets whom and how
often:

| Path | Who meets whom, how often | How it is settled |
|---|---|---|
| Timetables (groups on their way) | paths share a hex on overlapping days; chance rate times time together, per hex | bands talk or raid (`meetingEndsInRaid`); packs by the rivals' or enemies' rule; a slip-away check first |
| Contacts (above) | one side holds its ground; overlap, one draw a pair | engagement, or the rivals' and enemies' rules |
| Resident stand-ins | per hex, chance rate times resident bands; share-of-week between residents | their own slip-or-kill rule (`standInsMeetDraw`) |
| Neighbours (staying bands) | a yearly rate halving each hex of going apart, to 50 km; one meeting a pair a step standing for its count | talk or raid, by regard |
| Appointments | bands with the same place and month | all pairs talk |
| Kin visits | a hungry band to one within two hexes that has food | fed, debt remembered |
| Raids | a band sets out on a known home | fought at the target's home (`fightRaids`) |

So the same two groups meet by a chance rate or a yearly visiting rate, per hex or per pair, with or without a chance
to slip away or to choose ground, depending on the path that brought them together. The aim is one pipeline:

1. **Every party has a ground for the step** (`Sphere`): a holder its footprint; a group on its way its path by time;
   a staying band its home hex and those within a day's walk by its foragers' time (the spread the people's pressure
   already uses); a resident stand-in its hex.
2. **Contacts come about by chance or by intent.**
   - By chance: grounds overlap, times the chance rate (`contactsOf`).
   - By intent: a visit, an appointment, a kin visit or a raid. The traveller lays the target's hex onto its ground for
     the days it goes, with the reason. The neighbours' 60 a year becomes how often a band means to visit a friend.
3. **One engagement:** ground, detection, choice, outcome. A side's stance is its strategy for that ground, as it
   regards the other: kin and friends greet, strangers go by the strategy, a feud or hunger can make it a raid. Talk is
   the outcome where both greet (exchange, news, partners, feasts as now). A raid is an attack, with detection and
   ground as any other.
4. **One way of holding many meetings as one**, as the neighbours' meetings already do (`meetTimes`, `meetingFresh`):
   one settling a pair a step, standing for its count, at any step length.

In stages, each A/B'd:

1. resident stand-ins into the engagement;
2. staying bands' grounds, and neighbours' meetings by contact;
3. visits, appointments and kin visits as contacts by intent;
4. raids as attacks by intent.

### Stage 1 done: resident stand-ins through the engagement (9 Nov 2026)

A meeting where one side holds its ground (a pack about its footprint, or a resident stand-in in its hex) is over that
hex for the engagement (`engagedOver`), so a strategy on either side applies to it, and the stand-ins' bulk draw
(`standInsMeetDraw`) is kept for meetings where neither has one. The stand-in is made whole before each engagement and
what it lost is booked after, as at any meeting. Resident stand-ins keep the animals' rule (`strategyOf`), and their
meetings among themselves still go by the bulk draw: no strategy is set for them.

Seed 1, 400x600 km, 3 years, 6 days, packs staying. Without the switch, and with the animals' rule, the results are the
same as before, line for line. The engagements counted in the census weeks went from about a dozen to some 1,400-1,700
under the test strategies: packs meet the resident hunters of all their footprint. The strategies now show:

| strategy | leopards (contacts only / with stand-ins) | cheetahs |
|---|---|---|
| lair DEFEND, core GIVE_WAY_TO_STRONGER, fringe and outside YIELD | 130 / 114 | 10 / 2 |
| AMBUSH everywhere | 122 / 148 | 6 / 10 |
| ATTACK everywhere | 110 / 95 | 9 / 1 |

Falling on whatever they meet, lions and hyenas among it, costs leopards and all but ends the cheetahs; lying in wait
and striking only with surprise and the upper hand does them good.

### Stage 2 done: staying bands meet by their grounds (9 Nov 2026)

A band that holds its home hex has a ground for the step's meetings (`bandGround`): its home hex weighted 1 and each
hex about with land `BAND_ROUND_WEIGHT` (0.5), as its foragers go out and back by day. It is a holder like a pack about
its footprint. It meets other bands, packs and resident stand-ins wherever their grounds overlap, one contact a pair,
and two bands now contend (`contend`): a contact between them is a meeting of bands as any (`bandsMeet`, talk or
raid). Its hunting is as before: the ground is for the meetings only, for now. The neighbours' visits
(`neighboursMeet`) are unchanged, so bands now meet both by chance where their grounds overlap and by visiting.

Seed 1, 3 years, bands staying, packs staying. Without the switch the results are the same, line for line.

| | meetings of bands a year (neighbours' visits) | people | people born / died, last year | leopards | cheetahs |
|---|---|---|---|---|---|
| 400x600 km, 6 days, stage 1 / 2 | 4392 / 6123 (3522 / 3210) | 1533 / 1522 | 80 / 50, 86 / 51 | 100 / 121 | 7 / 7 |
| 400x600 km, 60 days, stage 1 / 2 | 3929 / 5960 (3491 / 3337) | 1587 / 1556 | 71 / 38, 70 / 52 | 23 / 14 | 6 / 10 |
| 800x1200 km, 6 days, stage 1 / 2 | 45077 / 71660 (38808 / 38906) | 13881 / 13483 | 685 / 576, 679 / 693 | 915 / 903 | 65 / 48 |

- Meetings of bands rise by half: the chance ones come on top of the visits. At 60 days they hold up as at 6
  (5960 against 6123), one contact a pair at any step length.
- More people die on the larger world (693 against 576), of the sicknesses that pass at meetings: the flux 19% of
  people's deaths against 14%, the cough 6% against 4%. Raids are about as many (72 at meetings and 6 on homes,
  against 62 and 15). Deaths to beasts barely change (taken by lion 2% against 1%).
- Meetings on the larger world 23.0 to 30.1 ms, the whole step 201.8 to 208.1 ms: each contact between bands is a
  meeting of bands, up to one a tick.
- **To settle in stage 3:** whether chance meetings come on top of the visits, as now, or the visiting rate (60 a year
  a hex apart) is the whole of how often neighbours meet, the chance ones counted in it. The figures for foragers
  (Hadza bands 35 km apart, Agta camps visiting almost daily) are of all contact, so the second is likelier right.

### Stage 3 done: visits are all contact between neighbouring bands; one settle for every meeting (9 Nov 2026)

Olli, 9 Nov 2026: the visiting rate is the whole of how often neighbouring bands are in touch, the chance meetings
counted in it.

- Two bands that both hold their home hex no longer come on each other by chance where their grounds overlap
  (`contend`): their every contact is their visiting (`neighboursMeet`). They still come on packs and resident
  hunters by chance where their grounds overlap.
- The bands of a band's own hex (a budded band and its parent) are among those it visits, at the full rate: before,
  they met only by their timetables, and with stage 2 by contact.
- **Every meeting is settled the one way** (`settle`): by timetable, by contact, by visit or at a gathering, by the
  strategies where either side has one other than the animals' (`engage`), else by the rules of the kinds that meet
  (a slip-away check, bands talk or raid, rivals', enemies' standing orders). Visits and gatherings come in through
  `settleAt`, with the visited band's hex as the meeting's ground.

Seed 1, 3 years:

| | meetings of bands a year (neighbours' visits) | people | people born / died, last year | flux, of people's deaths | leopards | cheetahs |
|---|---|---|---|---|---|---|
| 400x600 km, 6 days, stage 1 / 2 / 3 | 4392 / 6123 / 4154 (3522 / 3210 / 3535) | 1533 / 1522 / 1539 | 80/50, 86/51, 79/40 | 28% / 36% / 26% | 100 / 121 / 121 | 7 / 7 / 6 |
| 400x600 km, 60 days, stage 1 / 2 / 3 | 3929 / 5960 / 3667 | 1587 / 1556 / 1590 | 71/38, 70/52, 71/40 | 14% / 31% / 7% | 23 / 14 / 16 | 6 / 10 / 5 |
| 800x1200 km, 6 days, stage 1 / 2 / 3 | 45077 / 71660 / 45556 (38808 / 38906 / 39222) | 13881 / 13483 / 13819 | 685/576, 679/693, 690/610 | 14% / 19% / 13% | 915 / 903 / 834 | 65 / 48 / 42 |

- Without the switch the results are the same, line for line.
- Meetings of bands are back where they were before stage 2, and so are people's deaths and the sicknesses passed at
  meetings.
- People now kill more leopards, 4% of leopards' deaths against 1% before stage 2: bands come on the packs over all
  their grounds, not only at their camp. On the larger world leopards are 9% under stage 2. This is the people's
  pressure on the hunters showing through the meetings; to watch on the full map.
- Meetings on the larger world 23.0 ms, the whole step 189.3 ms, as before stage 2.
- Kin visits (`visitKin`) are a hungry band fed by one about it, a move of food rather than a meeting; they stay as
  they are.

### Stage 4 done: raids settled as any meeting, by intent (9 Nov 2026)

Every meeting now says how the two came to it (`Intent`: chance, visit, gathering, raid) and goes through `settle`.
A raid set out on a band's home (`fightRaids`) comes in through `settleAt` as a raid. Where a side has a strategy
other than the animals', it is an engagement with the raiders attacking (`Stance.ATTACK`, whatever their strategy says
of that ground), so the defenders get their detection, their choice and the ground they hold best. Else it is fought
by the rules of a raid as before (`fight`: works, dogs, fire, slipping away). A raid that breaks out at a meeting comes
in as the meeting does, through `bandsMeet`.

No band has a strategy yet, so the results are the same as stage 3, line for line, with the switch and without it
(400x600 km, 6 and 60 days).

### Where the unification stands

- **One way of coming on each other, in two kinds:**
  - by chance, where grounds overlap (contacts) or ways cross (timetables, for two parties on their way);
  - by intent: visits, gatherings, raids.
- **One way of settling** (`settle`): the strategies where a side has one (ground, detection, choice, outcome), else
  the rules of the kinds that meet. Giving way acts on the ground (`gaveWay`).
- **Left apart, on purpose:**
  - resident stand-ins among themselves (the bulk draw): no strategy is set for them;
  - kin visits: a move of food, not a meeting.
- **For people-groups with strategies** (bandits, war parties, scouts): give them a strategy (`strategyOf`) and a ground
  (footprint, or `bandGround`). Their contacts, visits and raids then go through the engagement with nothing else to
  change. What a fight between two groups of people does is today's `fight` for raids and `bandsMeet` for meetings.
  An engagement between two bands that comes to a fight goes to `fight`, the people's own rules (below).

### Bands' engagements fought by the people's own rules; who sees whom (9 Nov 2026)

- **Bands fight as people do:** an engagement between two bands that comes to blows goes to `fight` (their houses
  and palisade, the lie of the land about the camp, the bands about that stand with the defenders, slipping away,
  larders taken, driving off). The engagement only settles who falls on whom and whether unawares: its surprise
  (`ENGAGE_SURPRISE`) takes the place of the raid's own draw of surprise (scouts, moonlight), so neither is counted
  twice (`raidSides`, `edge`).
- **A band's ground for its strategy:** its lair is its camp's hex, its core the hexes about (`bandGround`, weights
  0.5). A band that gives way keeps off the hexes about that it gave up, never its camp (`gaveWay`). Test setting:
  `t.bandStrategy`, as `t.packStrategy`.
- **Friends greet:** two bands that are friends both ways (`FRIEND_TIE`) meet and talk whatever their strategies,
  but at a raid. Strangers and enemies go by their strategies.
- **Who knows of whom, by how they came to meet** (`Intent`):
  - at a visit or a gathering, each knows the other is there;
  - at a raid, the raiders know where they are going and come on as stealthily as they can (as one keeping out of
    sight); the raided may not see them coming;
  - by chance, `sightings`: one notices the other first, by its senses against the other's hiding, and the other
    notices it in turn unless the first keeps out of sight; two that both hide may pass unseen.
  - So surprise comes only from keeping out of sight. A first draft drew each side's sight apart, and nearly every
    fight was an ambush, at visits too, a band failing to see its neighbours walk into its camp.

Seed 1, 400x600 km, 3 years, 6 days, bands and packs staying. Without a strategy set the results are the same, line
for line. Fights and engagements are as counted in the census weeks:

| test strategy | people | people born / died, last year | raids a year | engagements fought (of them ambushes) | leopards | cheetahs |
|---|---|---|---|---|---|---|
| none | 1539 | 79 / 40 | 2 | | 121 | 6 |
| packs: lair DEFEND, core GIVE_WAY_TO_STRONGER, fringe and outside YIELD | 1548 | 88 / 45 | 2 | 95 (0) | 101 | 2 |
| packs: AMBUSH everywhere | 1541 | 83 / 42 | 7 | 826 (824) | 128 | 15 |
| packs: ATTACK everywhere | 1541 | 83 / 46 | 1 | 114 (0) | 80 | 4 |
| bands: lair DEFEND, core GIVE_WAY_TO_STRONGER, fringe and outside YIELD | 1451 | 75 / 54 | 35 | 1 (0) | 106 | 5 |
| bands: ATTACK everywhere | 536 | 23 / 189 | 111 | 15 (0) | 101 | 6 |
| bands: AMBUSH everywhere | 859 | 37 / 107 | 190 | 3653 (3629) | 122 | 5 |

- Packs: lying in wait helps leopards and cheetahs; falling on everything costs them; holding the lair and yielding
  the fringe mostly ends in giving way.
- Bands: holding the camp and yielding elsewhere costs few lives. A neighbour's camp lies in a band's core, where it
  gives way only to the stronger, so near equals sometimes fight. Bands that fall on every stranger, or lie in wait
  for them, all but destroy each other within three years: what bandits everywhere would do.
- The figures are first guesses (`ENGAGE_SURPRISE` 2, `ENGAGE_HIDING` 3, `ENGAGE_HIGH` 0.5, `ENGAGE_COVER` 0.3,
  `CORE_WEIGHT` 0.5, `YIELD_DAYS` 30).

### Stances by regard, and what bands saw of each other (9 Nov 2026, behind `t.bandStances`)

Olli, 9 Nov 2026: contact settings should be dynamic, per group, evaluated at least by regard, and probably something
the nets can learn; a simple model by regard to start. Luring can wait: with the present rules it is "yield until the
home base", the defenders choosing the fight where their odds are best. Scouting is close to yielding: we know the
enemy is there, we have seen it, and we fall back. What a band learns of another should be stored, and read by the
nets.

- **A stance toward each band it meets, taken afresh** (`stanceOf`, `bandStance`): a strategy set for test still
  overrides (`t.packStrategy`, `t.bandStrategy`). Else, with `t.bandStances`, a band's stance toward another band
  follows the hand-written rule `regardStance`:
  - friends greet;
  - a feud: it holds its camp; about it, if it is the stronger by `FIGHT_RATIO`, it stands its ground, else it falls
    back watching (`HIDE_AND_SCOUT`), as it does beyond. Whether to fall on a foe is its raids' to say (`raid`,
    `plotRaid`: readiness to raid, hunger, regard), not its stance's: a first rule that attacked a weaker foe at every
    meeting, visits and all, set feuds burning (800x1200 km: raids on homes 35 to 120 a year);
  - wary of the other (past `WARY_STANCE` 0.5 of the wariest regard): it holds its camp, gives way to the stronger
    in its core, and keeps its distance beyond (`YIELD`);
  - else it greets.
- **`GREET`**: two that both greet meet as their kinds do (two bands talk, `bandsMeet`, with its own chance of a raid
  by regard and hunger); against one that does not greet, a greeter gives way to the stronger. Only a meeting where
  some side takes another stance than its kind's own goes to the engagement (`engages`, `plainStance`).
- **Learnable:** the rule reads its inputs laid out as a network would (`StanceInputs`: tie, friend, feud, wariness,
  whether the other's strength is known, that strength against its own, the part of its ground). A network can take
  the place of `regardStance`; nothing trains one yet.
- **Scouting is falling back with eyes open:** `HIDE_AND_SCOUT` unseen lies low and watches; seen, it withdraws.
- **What a band saw of others** (`Band.seenStrength`, `seenDay`; `noteSeen`): their strength the last time it saw
  them, at a meeting, a fight, or an engagement where it saw the other (a scout's watching is for this), for
  `SEEN_DAYS` (90). Its stance goes by it (`knownStrength`: unknown, it reckons them its equal). The people's network
  reads it (appended to `BandInputs`, so nets trained before give it no weight): the strongest band seen lately against
  its own strength, the strongest hostile one, and how many it saw.

Seed 1, 3 years, bands and packs staying. Without the switch, and with it but without stances, the results are the
same as before, line for line (the new inputs change nothing for nets trained before them).

| | people | people born / died, last year | raids a year (at meetings / on homes) | meetings of bands | leopards | cheetahs |
|---|---|---|---|---|---|---|
| 400x600 km, 6 days, without / with stances | 1539 / 1546 | 79/40, 83/44 | 2/0, 1/0 | 4154 / 4208 | 121 / 109 | 6 / 7 |
| 400x600 km, 60 days, without / with | 1590 / 1584 | 71/40, 71/39 | 1/0, 1/0 | 3667 / 3500 | 16 / 20 | 5 / 6 |
| 800x1200 km, 6 days, without / with | 13819 / 13844 | 690/610, 690/584 | 59/35, 45/57 | 45556 / 41891 | 834 / 896 | 42 / 78 |

- Most bands greet most others: the meetings of bands hold up, and the engagements counted in the census weeks are a
  few dozen, nearly all of them one side giving way.
- On the larger world there are more raids on homes (57 against 35) and fewer at meetings (45 against 59): feuding
  bands no longer meet as friends and come to blows there; they hold their camps and fall back, and the feuds are
  fought out by raids set out. People's deaths are about as many (584 against 610).
- Meetings on the larger world 24.0 ms, the whole step 193.6 ms.

### Who is present, and staying true to the record (9 Nov 2026, behind `t.bandStances`)

Olli, 9 Nov 2026: a small pack moves, fights and defends together; a larger band goes about in smaller groups and is not
in one place at a time. A planned raid has adults attacking while children and the old stay home; the defenders may
have the whole band at home. And keep it simple: in the record, violence among foragers is mostly within the group;
neighbouring bands are mostly kin and friendly and should by default regard each other so; planned aggression comes
only with something valuable (fixed property, houses, fields, rich natural spots). This is the early stone age, of
hamlets and foragers: most of this is groundwork for later.

**How it was:** packs and bands alike met and fought as one body. A band's strength was all its adults, elders and
wounded among them. In a planned raid every adult of the raiding band went and every adult of the raided fought, and
deaths fell among all of them. Children never fought.

**Now, with `t.bandStances`:**
- **Who fights a planned raid:** a raiding party of `RAID_PARTY` (half) of the raiders' able adults (`raidParty`: its
  grown less the wounded and lamed, `Band.ableShare`); the rest, its children and old stay home. The walk there and
  back is charged to the party. The raided fight with their able adults at home. Deaths fall among those who fought.
- **Who is worth raiding:** planned raids go only against bands with something fixed to take or hold
  (`Band.livesFixed`: living in their houses, fresh sowing, a palisade). Bands on the move are not raided.
- **Neighbours greet:** a band greets every band it has met, however wary of it; only a stranger never met, and one it
  is wary of, does it hold off at its camp and keep its distance from. A feud is still held off at the camp.
- **Left as it was, for now:**
  - a band meeting beasts by chance meets them as a whole band, not as one foraging party;
  - a raiding band's camp is not weaker while its party is out;
  - raids that break out at meetings, by hunger, feud or trespass, are as before;
  - "rich natural spots" do not yet make a band worth raiding.

Seed 1, 3 years, bands and packs staying. Without the switch, and with it but without stances, the same as before,
line for line.

| | people | people born / died, last year | raids a year (at meetings / on homes) | meetings of bands |
|---|---|---|---|---|
| 400x600 km, 6 days: no stances / stances / with presence and the record | 1539 / 1546 / 1538 | 79/40, 83/44, 83/49 | 2/0, 1/0, 0/0 | 4154 / 4208 / 3665 |
| 400x600 km, 60 days: the same | 1590 / 1584 / 1586 | 71/40, 71/39, 73/38 | 1/0, 1/0, 1/0 | 3667 / 3500 / 3517 |
| 800x1200 km, 6 days: the same | 13819 / 13844 / 13882 | 690/610, 690/584, 692/568 | 59/35, 45/57, 39/75 | 45556 / 41891 / 43125 |

- People die fewest of the three on the larger world (568 a year). Fewer raids break out at meetings.
- Raids on homes are more (75 a year against 57), although only bands that live fixed are raided now. One seed; a
  likely part of it: a party of half the able adults is weaker, its raiders die more, and the grudges they leave set
  the next raid (`RAIDER_RESENT`). To watch on the full map and over more seeds.
- Meetings on the larger world 22.5 ms, the whole step 185.6 ms.

### For the future: more rules for violent encounters

Noted by Olli, 9 Nov 2026, for later:
- **Pillaging:** part of it is there (a raid's winners carry off part of the larder, `RAID_TAKE`; caches are robbed,
  `robCaches`; a beaten band may be driven from its home, `driveOff`). Herds, goods, stores in houses and captives are
  not taken yet.
- **Burning:** only the burning-off of land (`burnOff`). Burning a beaten band's houses, palisade, stores or fields is
  not modelled.
- **Luring,** drawing the other deeper into one's ground than the ground the two share, and more strategies and rules for
  fights in general.
