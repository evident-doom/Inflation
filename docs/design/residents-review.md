# Residents review: moving, feeding, habitat, and a profile

A fourth review, after [consolidation-review.md](consolidation-review.md) (movement), [life-review.md](life-review.md) (eating, deaths, skills, the ways networks) and [culture-review.md](culture-review.md) (regard, customs, rites). This one reads the residents: the animals kept as numbers per km² of each place rather than as groups, how they feed, breed, die, move and disperse, and where they meet the groups. A quick profile of the step closes it.

The residents share most of their rules with the groups already:

- **Hunger, the weather and breeding** are the groups' rules (`Hazards`, `breeding`, `youngDue`, `YOUNG_HARDER`), and kept herds now use them too.
- **Predation** uses one rule for what a hunter can catch (`catchable`, `closingIn`), whether the hunter is a resident or a group.
- **Dispersal and drift** honour the ground: each kind crosses only the borders it can, never a strait it cannot swim or a cliff it cannot climb.

The frays are in how residents move compared with groups, in a few kinds singled out by name, and in the shortest-path search being written out four times.

## 1. Residents move over the map's fixed going, groups over the week's

Groups walk by this week's going (`stepEffort`, PlanReach). That is the map's crossings, made harder by lying snow (about twice the effort in 15 cm) and easier along worn paths, read at the height band of their place.

Every resident move uses the map's fixed crossings instead:
- the spill to neighbouring places (`flowWays`, `spill`);
- the drift of dispersers (`drift`);
- the dispersal ground (`dispersalAbout`).

The flow ways are worked out once because borders and places never change and the flows run across every place every tick. That is a deliberate choice for speed, and paths matter little to animals. But snow does matter: a resident wolf disperses through a metre of snow as easily as over bare ground, while a pack of wolves as a group struggles through it.

**Fix:** keep the flow ways precomputed, and scale each step's ease by the snow where it arrives, with the factor `stepEffort` uses. That is one multiply per flow, read off the week's snowpack. This changes results, so it gets an A/B.

## 2. Kinds singled out by name

The Catalogue marks categories by named lists (`roamingResidents`, `residentRivals`, `migratory`), which read as the kind's own facts. But a few rules single out one kind by identity:

- **Lions disperse 30 km over 300 days**, every other kind 60 km over 90 days (`dispersalKm`, `dispersalDays` in ResidentMoving, `if (s == Catalogue.LION)`). These are facts about a kind, so they belong on `Species`, as every other kind's figures do.
- **Cheetahs start** at `tuning.cheetahStart` of their usual numbers and **away from lion country** (`HexGroups`, `keepOutOfLionCountry`). The rule itself is right (Durant 1998: cheetahs keep off lion ground), but it is written for the two kinds by name. A `Species.keepsOffKind` (lions, for cheetahs) would say the same of any kind, and the start rule would read it.

**Fix:** the dispersal figures become `Species` fields (lions 30 km and 300 days, the rest at today's defaults). The start placement reads a kind's "keeps off" kind rather than naming the two. The first leaves results unchanged; the second too, since only cheetahs would carry it.

## 3. The shortest-path search, written four times

Four hand-written least-effort searches walk the hex map:

- `Reach.searchFrom`: the groups' reach, with a packed queue, a thread's own buffers and ties broken by hex, so the same on every core;
- `PlanWayOut.searchWayOut`: the way out of a peril, over this week's going, with the weeks the peril strikes;
- `dispersalAbout` (ResidentMoving): the residents' dispersal ground, ties broken by hex;
- `HexMap.efforts`: the map's own least effort from a hex.

(A fifth is in DoomTrace, a trace only.) Each has its own queue and its own way of breaking ties. A tie broken another way changes which of two equal paths is taken, and so a result.

**Fix:** one search over an edge-cost array (`Reach`'s: packed queue, ties by hex), with a callback for what each caller keeps (distances, the way back, the weeks of a peril). The others call it. The dispersal search already breaks ties as `Reach` does, so it should move unchanged. `PlanWayOut` and `HexMap.efforts` break ties by the queue's insertion order, so moving them may change a rare tie; a line-for-line check shows whether it does.

## The profile

One year on m5-1 (`profile=jfr`, report by `ProfileReport`). Another session's runs used part of the machine (13.5% on average), so the absolute times are rough, but the shares hold.

- **The step:** 590 ms on average. The biggest phases are the groups' births and deaths (23%), the meetings (20%), the hunt (14%) and the residents' grazing (10%).
- **Shelter worked out afresh.** The hottest frame is `Niches.shelter` (5.3% of all samples). The resident hunters' feeding (lions, wolves, hyenas and jackals: 60% of the residents' 746 thread-ms a step) calls it inside `closingIn` for every hunter, prey kind and place. A prey kind's shelter at a place does not depend on the hunter, so working it out once a week per kind and place, as `groundShelter` already is for its ground part, should cut most of it.
- **Parallel jobs too small to pay.** Six of the residents' loops ("weigh", "going", "going, out", "going, in", "going, gathered", "weigh, spread") split about 112,000 jobs a step of 0.1–0.8 µs each, and keep only 0.5–1.0 cores busy: the cost of dealing out the jobs is as large as the work. Dealing them out in chunks of a few hundred places would let them use the cores.
- **On the main thread:** the groups' reach search spends 28% of its time in `HexList.add`, and booking a group's outcome spends 28% in a `ConcurrentSkipListMap` (`ThreadSums.add`). Both are worth a look, not yet read: whether the reach's list can be sized up front, and whether those sums need a concurrent map where they are booked.

None of these changes a result, so each can be checked line for line.

## Order

1. Items 2 and 3, and the three profile fixes: none should change a result, and each is checked line for line.
2. Item 1 on its own A/B: the snow in the residents' going.

## Done

- **Item 2:** a kind's dispersal distance and days are `Species.dispersalKm` and `dispersalDays` (lions 30 km and 300 days, the rest 60 and 90). A kind's start away from another's country reads `Species.keepsOff` (cheetahs: lions), in `HexGroups.keepOffKind`. `tuning.cheetahStart` stays, a knob for one kind by its name.
- **Item 3:** one search, `LeastEffort` (world), one per thread (`HexMap.searcher`): least effort first, of equals the lower hex. The groups' reach, the way out of a peril (both passes), the residents' dispersal ground, `HexMap.efforts` and the doom trace all drive it; the planner's own heap and `ReachScratch` are gone.
- **Shelter:** a prey kind's shelter at a place (`HexFauna.preyShelter`) is worked out once for its grown, young and packs in a hunt, and once for grown and young in `game` and in the hunt of kept herds, not once for each. It is not shared between hunters or kept for the week: the plants it reads can change between one place's feeding and another's.
- **Booking outcomes:** the outcome loop runs in `WeekStats.summedByThread`, so its tallies go to the thread's own sums and are merged into the sorted ledgers once.
- **Not changed:** the small parallel jobs. `IntStream.range(...).parallel()` already deals the jobs out in chunks; the jobs look tiny and the cores idle only under `profile=jfr`, which times each job and opens an event for it, an overhead as large as the job. Nor `HexList.add` in the reach search: the list keeps its size from search to search, so its share is the samples landing on the search's own writes.

All of these left one year on m5-1 the same, line for line.

