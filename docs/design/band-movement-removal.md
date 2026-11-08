# Bands without weekly movement: a plan

Written 8 Nov 2026 on top of the variable-step branch (`claude/project-thread-xvajrg`, 63 commits past `main` at #696);
revised the same day with Olli's decisions. Humans first; packs later, by the same route.

Moving to a variable step with no weekly movement is an architectural decision (Olli). It stands unless we run into
really serious trouble.

## The aim

A band has a **base camp in a home hex**. Everything a band does from day to day is reckoned against that camp and
never moves the band on the map:

- foraging and hunting
- fetching wood, stone and salt
- herding
- visiting, feasting and trade
- raids

The hex is the model's resolution for people. The geography, weather and plants keep their finer grid. Inside the hex
a band can at most camp on its **low or high ground**.

The band itself moves only when it **buds**. When a band splits, the part that leaves takes a free adjacent hex. People
spread across the map by slow budding to neighbouring hexes, never by long treks.

Bands deal with their neighbours, in adjacent and nearby hexes, all the time, by agreed rules (the later contracts
model). They do not have to share a hex to meet, feast or trade.

## Decisions (Olli, 8 Nov 2026)

| Question | Decision |
|---|---|
| Density | Forager bands start at Natufian densities: **one band per hex**, as a convenient simplification for now. More people per band, or more bands per hex, come with technology, and densities rise with settling and farming. Our estimates put a hex's food limit well above 25 people: the hypothesis is that food was not what kept densities down. |
| Influence beyond the hex | Later. For now neighbouring hexes simply have bands of their own. |
| Place inside the hex | At most the hex's low or high ground. |
| Capacity | The existing rules for bands sharing a hex (shared stocks and the ecology already modelled). Further limits come later if needed. |
| Survival mode | Left for now. |
| Storms and recruiters | Dropped: they do not fit this model. |
| Hex becomes uninhabitable | Its people disperse to neighbouring hexes. Not a priority yet. |
| Base | Continue on top of the variable step. The other machine is only training nets on that branch and not changing it, so the branch can be merged to `main`. |

## Forager territoriality: a quick look

This is a short study, a handful of searches, not a review. It supports "one band, one hex" as a starting
simplification, and it points the network model at *social* boundaries rather than walls.

- **When defending ground pays.** Dyson-Hudson and Smith (1978), "Human territoriality: an ecological reassessment",
  *American Anthropologist* 80: 21-41.
  - Their economic-defensibility model treats defence as a cost-benefit choice.
  - Territory pays where resources are **dense and predictable**, and only when there is competition for them.
  - They tested it on the Northern Ojibwa, the Basin-Plateau peoples and the Karimojong.
  - Natufian ground is the textbook case of dense, predictable resources (stands of wild cereals, oak and almond,
    gazelle on their routes), which fits a band holding one territory.
- **Defending people rather than ground.** Cashdan (1983), "Territoriality among human foragers", *Current
  Anthropology* 24: 47-66.
  - Among four San groups she found the most territorial where resources are **sparsest and least predictable**, the
    opposite of the model.
  - The reason is that people defend **access to the social group** (permission, kinship, reciprocity) rather than a
    perimeter. Social boundary defence is cheap where a perimeter would be impossibly long.
  - Hill's comment in the same issue disputes the data and the argument.
  - Either way, this is the mechanism the contracts model needs: neighbours come in by agreement, and the agreement is
    what is defended.
- **Base camps.** Kelly (1995) and Binford (2001): foragers work out from a home base and back. The base is moved within a home range as
  the land near it is used up and with the seasons. Bar-Yosef describes Natufian base camps as having a large
  catchment with a **radiating** mobility pattern, and smaller short-lived camps used for particular resources.
  - So "everything reckoned against the base camp" is the ethnographic pattern, not just a simplification.
  - Moving the base inside the home range is what our camp fouling and worn ground already stand in for.
- **Natufian catchments are small and well worked.** El-Wad on Mount Carmel, one of the largest hamlets, worked its own
  catchment hard. Some of its materials come from within about 12 km, while the basalt came from 60-120 km off, far
  beyond any day's range.
  - The near part fits one hex, about 10 km across.
  - The far part is **exchange or forays**, not the band's own ground: links to other bands, which is where the network
    model comes in.
- **Territory grows more slowly than population.** Studies of ethno-linguistic groups find territory scaling
  sub-linearly with population, and the area per group rises faster with latitude than group size does.
  - Bands in the cold north would range more widely.
  - With one band per hex, that shows up as fewer people per hex in the north, not as bigger hexes.

## How a band moves today

Every step, `HexGroups.chooseAndMove` runs `GroupPlanner.plan` for every group, bands included:

- `searchReach`: a least-effort search over hex borders.
- For every reached hex × place: `scorePlace`, `deathsAt`, crossings, perils, `leavingCost` and `moveShort`; for a
  network, `fillFeatures`.
- For bands also:
  - `bandPull` (crops, salt, raid, feast, herd loss, appointment)
  - an explore draw or a reckoned move (`Exploring.kt`, `ReckonedMove.kt`)
  - a scouting party's target
  - `searchWayOut` under threat

The planner hands back a **place and a hex path**, and `timetable()` turns the path into day stamps (`g.days`).

The rest of the step reads that path:

| Reader | What it does with `g.path` / `g.days` |
|---|---|
| `meet` (`HexMeetings.kt`) | Builds stays by hex from the timetable; meetings are where two timetables overlap. A meeting can cut the path short, and the band ends its step there. |
| `PathWear.week`, `HexRoutes` | Paths worn by the hexes walked; routes and landmarks built from the wear. |
| `hunt` / `forage` (`GroupFood.kt`, `ForageOffers.kt`, `Kills.kt`) | The sweep is shared over the hexes passed (`/ g.path.size`); gathering offers along the path; the hunting ground reaches along the way. |
| `learnCountry` (`PeopleCamp.kt`) | The band comes to know the hexes it walked and what it saw from them. |
| `campAndCache` (`PeopleCamp.kt`) | A new camp hex means caching what cannot be carried, leaving goods, dropping heavy tools, resetting `campWeeks` and learning `stayWeeks`. |
| `PeopleWarmth` | Moving may cost the band its fire; shelters have to be rebuilt. |
| `GroupFood` time | Moving days (`moveDays`) are taken from the week's work. |
| `crossWater`, storms and recruiters | Drowning on sea crossings; storms struck on the way. |
| `bandInputs` (`HexPeople.kt`) | The people networks' `MOVE_KM`, `MOVE_DAYS` and `LOAD` inputs. |
| `HexCulture` | Known homes along the path are checked against who is actually there. |
| Census, DeathWarning, Foresight trace, viewer | Counts of moves; the viewer draws trails. |

The planner also does two jobs for the rest of the step that are easy to miss:

- `GroupPlanner.beginChoosing` fills **`headsHere`** (the heads of each kind in each place). `GroupHazards` reads it for
  crowding.
- `takeStock` fills the man-eaters and strongest hunters about each hex.

Both have to be kept, filled elsewhere, when bands stop planning.

## Measured (8 Nov 2026)

The setup for every run below:

- **World:** seed 1, 400 × 600 km, 3 years, 41 bands at the start.
- **Nets:** `night-c9fe2776-N3-kept-last`.
- **Machine:** Olli's PC, 16 cores.

These are single runs, so the outcomes carry one weather's noise.

**Moving bands.** The step's length sets how mobile they are:

| Step | Choosing, ms a step | Share of the step | Camp moves a year | Band-steps that moved | Larder days |
|---|---|---|---|---|---|
| 6 d | 10.6 | 10% | 4.0 | 9.6% | 16 |
| 30 d | 30.2 | 13% | 17.1 | 29.7% | 7 |
| 60 d | 60.3 | 18% | 26.8 | 42.4% | 2 |

So a 60-day step makes moving bands nearly seven times as mobile as weekly ones, and they carry almost no stores.

**Phase 1 (`t.bandsStay`), against moving.** These are timed runs of the Phase 1 worktree; its runs with the switch off
are the same model as the Phase 0 runs above.

| Step | Choosing, ms (moving → staying) | Whole step, ms | People at the end, adults / young (bands) | Nominal limits |
|---|---|---|---|---|
| 6 d | 6.1 → 5.3 | 104.9 → 95.7 | 1126 / 463 (48) → 1129 / 463 (49) | all held, both |
| 60 d | 56.1 → 41.0 | 294.6 → 322.7 | 1116 / 484 (48) → 1048 / 458 (42) | all held, both |

- **Staying bands still switch ground:** they move between their hex's low and high ground in 2-4% of steps, at either
  length.
- **The census counts no camp moves:** its ethnographic line now reads "camps moved a year 0.0" against the record's
  5-60. Phase 1's camp-shift rate still has to be added.
- **The time saved is small at this stage.**
  - Bands still go through the planner: two places' scores, their deaths and the network.
  - The step's setup for choosing (game by place, the sense of direction, the expectations) is made for every kind
    whatever bands do.
  - Packs still choose as before.
  - The real saving comes when the planner is skipped for bands altogether, and later for packs.
- **The 60-day staying run lost more people in its last year** (97 dead, against 38 moving): one run, to be checked
  with replicates.

## Where it clashes with the variable step

The variable-step branch makes most slow parts read the step's length as it stands: tick by tick, or as a closed form
over the step's days. Groups' movement still happens **once a step**, so any step length other than 6 days changes how
often a band moves. A 60-day step is ten weekly decisions folded into one. The clashes, worst first:

1. **One move a step.**
   - The reach grows only as `ticks^0.5` (`stepShift`, `t.stepReach`): a 60-day step allows one hop of about 3.2× a
     week's reach, then the camp is held for all 60 days.
   - `campWeeks` and `stayWeeks` come in multiples of the step's length, so a mobile band cannot be mobile at long
     steps.
   - `HexFeatures.STEP_LENGTH` and `t.stepReach` exist to paper over this.
2. **Timetabled meetings.**
   - Meetings come from the overlap of the path timetables over `[0, days]`, capped on the branch at `min(met, ticks)`
     per pair. A long step's path and timetable are only a guess.
   - Meetings can also move a band (`stop`), so meetings and movement are tied together.
3. **Per-move events** happen once per change of camp, so how often they happen depends on step length, not on the band's way of life:
   - caching and leaving goods
   - dropping heavy tools
   - losing the fire
   - rebuilding shelters
   - drowning on a crossing
   - storms struck on the way
   - `learnCountry` from the path
   - path wear
4. **The leaving cost against survival steps.**
   - `leavingCost` / `Band.leavingDying` is a year's deaths, weighed against `survivalSteps`, which measures the chance
     spread of one step's deaths.
   - That spread changes with the step's length, so the same move is weighed differently at 6 and at 60 days.
   - The explore draw's unit (`EXPLORE_STAY`) has the same problem.
5. **Countdowns in steps, not ticks.**
   - `Raid.weeks` counts down by 1 a step (`PeopleRaids.kt:50`), so at 60 days a raid party stays out for 4 × 60 days.
   - `Unlucky.weeks` (`UNLUCKY_WEEKS`) is the same kind of countdown.
   - These are plain bugs on the branch whatever happens to this plan.
6. **Path-shared foraging.** The sweep `/ g.path.size`, `smallSweep` and the `gatherAt` offers along the path assume the walk is
   spread evenly over the step.
7. **Season memory.** `remember` keeps one place a month (`seasonPlace`), which the planner heads for. At steps of 30
   days or more, months merge or are skipped.
8. **The ways network reads the mover.** `g.plan = policy.planned(g, candFeats[k])`: the bands' ways network reads how the people mover saw
   the chosen place.

On cost: choosing is a search plus a hazard reckoning for every candidate place, for every band, every step. It is the
one slow part whose cost grows with reach, and reach grows with step length. I could not time it here, because the
sandbox blocks Gradle's loopback connection; Phase 0 measures it.

## The plan

Every phase is A/B'd with `testWorld ab` against its own base, at 6 days and at the lengths `ppo.stepChoices` draws.
The new model runs behind a switch, `t.bandsStay`, until it is the default; packs keep the planner throughout.

### Phase 0: base and measure (no model change)

- Merge the variable-step branch into `main`, once you give the word. The other machine only trains on it. If a
  retrained baseline comes out of that training, keep track of which commit it was trained on.
- Fix the two countdowns (`Raid.weeks`, `Unlucky.weeks`) to count ticks.
- Profile `hexFauna --world m455-1 --years 3` with `profile=fine` at `t.stepDays` 6, 30 and 60. Record:
  - the share of the step spent in "choosing: groups", bands apart (`Profile.kind("choosing", "bands")`);
  - meetings;
  - "groups: wearing paths".
- From the census, record camp moves a year, `campWeeks` and `stayWeeks`. These give the baseline the in-hex mobility
  rate has to match.

### Phase 1: a band holds its home hex (`t.bandsStay`)

- **Home hex.** A band's home is `hw.placeHex[campPlace]`. Under the switch it skips `GroupPlanner.plan`. Its path is
  its one hex and its `days` is `[0]`, so the step's readers still work, each with a single hex.
- **Low or high ground.** Once a step the band picks its hex's low or high ground, or keeps the one it is on. This
  needs no search, path or timetable.
  - Today a hex's places are up to five height bands. For people, they would be grouped into low (the lowest place)
    and high (the highest), and the camp put on one of them.
  - The pick is a short hand-written score of the food, warmth, water and snow terms already in `fillTerms` and
    `scorePlace`, without the travel, pull and leaving terms.
  - It has no reach, so it does not depend on the step's length.
- **Foraging is the home hex.**
  - The band forages all of its hex's places. Its camp's ground is nearest, the other band costs a walk, as
    `ForageOffers` now offers along the path.
  - It never forages in another hex. The neighbours have their own bands.
- **Moving camp within the hex is a rate, not a move.**
  - Camp fouling (`fouledWeeks`) and `WornGround` already make the camp's own ground dearer the longer it is used.
  - A **camp shift** inside the hex, from the land near camp running out or with the season, partly resets them and
    costs days of work. It never changes the hex.
  - `campWeeks` and `stayWeeks` count between shifts, so they keep their meaning at any step length, and the census'
    mobile/settled line reads them as before.
- **Keep what the planner filled.** `headsHere` and `takeStock`'s tables move into the step's start (`chooseAndMove`) for all groups.
- **Retired with the switch** (bands only):
  - storms and recruiters (dropped altogether: `t.hurricane*`, `t.recruiter*`)
  - `crossWater`
  - scouting parties (`t.parties`)
  - the appointment pull
  - the explore draw and the reckoned move

### Phase 2: rebuild what read the path

| Was | Becomes |
|---|---|
| Meetings by timetable overlap | **Neighbour interaction by distance.** A band's neighbours are the bands within N rings (1 to start: the adjacent hexes, and its own if it shares one). Each pair gets interactions (news, gifts, words, customs, tellings, and a raid if their regard comes to it) drawn at a rate a day that falls with distance and rises with ties. `visitKin` already works this way, within two rings and without moving. This is the seed of the contracts model. |
| Foraging along the path | The home hex (Phase 1). What the hex lacks (good stone, salt, ochre) comes from **forays** (adults away for days of walking and working, as rites already do: `RITE_DAYS` plus the walk) or from neighbours by exchange. `lacking`, `PlaceMemory.RARE` and `saltWeeks` already know where those things are. |
| `learnCountry` from the path | The home hex and its ring are known. Forays and neighbours' tellings add the rest (`memory.hear`, `HexRoutes.tell`). |
| `PathWear`, `HexRoutes` | Kept for packs. For people, wear comes from forays and from the traffic between linked bands, or is retired until the network model needs it. |
| Raids: the band walks to the target's hex (`Band.raid`) | A **war party foray** to a neighbour's home: adults away for days. `fightRaids` already settles a raid at the target, so only the getting there changes. |
| Rounds and rites (`seasonPlace`, `appointPull`, `riteGatherings`) | A rite is held by the band's adults going to the site and coming back, as now. A gathering is the bands that hold a rite at the same site in a month, as now. `seasonPlace` becomes the low or high ground for each month. |
| `campAndCache` per move | Runs only on budding. Camp shifts within the hex carry their stores. |
| Fire and shelter lost on moving | Only on budding, or a camp shift within the hex (days to rebuild). |

### Phase 3: herding

- Kept herds now graze where the band camps (`herdForage(p, ...)`), and their loss is a term in the planner's score.
- Without moves:
  - herds graze the home hex's places;
  - the low/high choice is the season's pasture (summer high, winter low), which covers the vertical transhumance a
    hex can hold.
- Herding further afield waits for survival mode's "some of the band away for a while, then back".

### Phase 4: budding

- Replace "a split-off band starts at the parent's place and the planner walks it away" (`splitOff` + `plan`) with
  `findHome(n)`:
  1. Among the **adjacent hexes with no band**, take the most hospitable: the start weight terms (food, water, warmth,
     plants known), by the leaving part's way of life.
  2. If every adjacent hex has a band, it **stays in the parent's hex**. The two then share the hex under the existing
     rules: shared stocks and ecology.
     - Crowding then works through what is already modelled: depletion, fouling, disease and regard.
     - Once a hex holds two bands, any later split from it looks for room again among its own adjacent hexes, so
       people spread one ring at a time.
  3. It inherits the parent's ties (`SPLIT_TIE`, as now), so the pair are each other's first neighbours in the network.
- The new band takes its share of goods and food over to the new hex: the one real relocation, through `campAndCache`'s
  leaving (caching, carrying, heavy tools left).
- Merging (`mergeAndPair`, `joinBand`) needs both bands in the same hex today. Under the new model it is within the
  neighbourhood, with the smaller band moving into the other's hex.

### Phase 5: starting the world in order of hospitability

- Today each starting band's place is drawn at random by `startWeights` (`HexGroups.init`).
- Instead:
  - rank the land hexes by the start weight for foragers;
  - put **one band in each hex, best first**, until the people the start asks for (`t.peopleStart` × usual) are
    placed;
  - put each band on the hex's better ground of low or high.
- The world starts with the best country taken and the rest empty, and budding does the spreading.
- `startVillages` and `startHerders` choose among the bands already placed, as now.
- The test worlds (`m455-1..3`, `lake-1`, `small-1`) were settled with moving bands, and a new start invalidates them,
  so they are rebuilt once the switch is the default.

### Phase 6: learning

- **Retire the people move network** (`Role` people, `people.mlp`, the people's `HexFeatures`). Low or high is
  hand-written.
- The **ways network** reads the mover's view of the chosen place (`policy.planned`, `HexPpo.planned`).
  - Give it the camp's own terms instead.
  - Drop `MOVE_KM`, `MOVE_DAYS`, `LOAD` and `STEP_LENGTH` from the band inputs.
  - `.names` keeps a network working when inputs change (`docs/design/model-compatibility.md`), but this changes
    enough that a new baseline is likely needed.
- `HexPpo`, `EcoCheck` and `Outcomes` keep working for packs. For people, only the ways are recorded.
- `ppo.moverKitWeight`, `ppo.waysPlaceWeight` and `ppo.waysTrainMover` lose their meaning for people.
- The nets the other machine is training now on the variable step include a people mover. Its ways networks are what
  carries over.

### Phase 7: clean up, once the switch is the default

- **Code that goes for people:**
  - in `PlanBands.kt`: `bandPull`, `cropPull`, `raidProgress`, `saltProgress`, `feastDraw`, and `herdLoss` as a move
    term
  - bands' paths through `Exploring.kt`, `ReckonedMove.kt` (`reckonedWalk`, `relayLoads`, `moveCostDays`),
    `PlanWayOut.kt`
  - `leavingCost`, `moveShort`, `keepOff`, `appointPull`
  - `ScoutingParty.kt`
  - `HexHurricane`, `Peril` and the storm and recruiter census
  - people's `HexBearings` and `astray`
- **Kept for packs** until they go the same way: `GroupPlanner`, `PlanReach`, `PlanScore`, timetabled meetings and `PathWear`.
- **Tools:**
  - retire for people: `HexPullCheck`, `HexTraps`, the move parts of `HexPeopleLeverage`, `HexChoiceEffect` and
    `HexPolicyDiff`, `moveTrace`, `perilTrace`, and `doomTrace`'s "where it went"
  - rework: the viewer's Plan tab ("where it camps" becomes low or high and camp shifts) and the follow window's trail
- **Nominal:** the ethnographic line counts camp moves as camp shifts within the hex. Its references still hold.
- **Config keys to drop:** `t.stepReach`, the gate slots of `t.foragerSlots` and `t.plannerSlots`, `t.hurricane*`,
  `t.recruiter*`, `t.parties`, and `t.bandsStay` itself at the end.

## Order of work

1. Phase 0: merge the branch, fix the two countdowns, profile.
2. Phase 1 behind `t.bandsStay`, A/B'd at 6 and 60 days. This removes the variable-step clash for bands and shows what
   it saves.
3. Phase 5, with Phase 4 straight after: the start and budding go together, since one band per hex needs both.
4. Phase 2, meetings first: neighbour interaction is the change the contracts model builds on.
5. Phases 3, 6 and 7.

## Sources (territoriality study)

- [Dyson-Hudson & Smith 1978, via the economic defensibility literature](https://link.springer.com/article/10.1007/s10745-020-00141-9)
- [Cashdan 1983, Territoriality among human foragers (bibliographic record)](https://mapaspects.org/biblio/ref_7063/index.html)
- [Cashdan 1983 and the comments on it (Utah collection)](https://collections.lib.utah.edu/dl_files/82/c1/82c1609ed33d05319d577bb7264c091e2034776f.pdf)
- [El-Wad Terrace catchment and the distant basalt](https://link.springer.com/10.1007/s10816-024-09653-w)
- [Natufian hamlets, contracted territories](https://link.springer.com/article/10.1007/s41982-024-00200-3)
- [Bar-Yosef's base camps with radiating mobility, cited in Nexus](https://journals.mcmaster.ca/nexus/article/view/176/143)
- [Territory size against population and latitude](https://pmc.ncbi.nlm.nih.gov/articles/PMC4157219)
- [Kelly and Binford on base camps within a home range](https://www.cambridge.org/core/journals/american-antiquity/article/scaling-the-size-structure-and-dynamics-of-residentially-mobile-huntergatherer-camps/3A1BC468ED01A37BA8FFCE003D1C7B44)
