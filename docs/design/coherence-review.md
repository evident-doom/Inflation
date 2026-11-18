# Coherence review: geography, paths, and the new mechanisms (14 Nov 2026)

Olli asked for a scan of the code for the same kind of inconsistency the band-relations work found (visits, events and
other movement across the map not honouring the ground, and leaving no paths), and a look at whether the new mechanisms'
abstractions hold together. This is the review; nothing in the code is changed by it.

The yardstick is the rule the other session set (Olli, 13-14 Nov 2026): **every going of people between hexes goes by
the ground, wears the way and pays its walk.** PR #762 (not yet merged when this was written; branch reviewed at
a152911f) makes it one movement logic, `Movement.kt`:

- `findWay`: the least-effort way over the hexes' borders (`planner.stepEffort`: slope, cliffs, rivers and fords, snow,
  worn paths), with its hexes; no way, no going;
- `trip` / `tripAlong`: some of a band go there and back, the way worn by how many went and how often, the walk charged
  (`WalkPaid`);
- `walkOver` / `moveCamp` / `joinOver`: a whole band goes one way.

It removes `tripKm`, `wearBetween` and the greedy straight wear (`PathWear.travelled`). Each finding below says whether
#762 settles it.

## A. Things that move, meet or act across the map without the ground

Ordered by how much they matter.

### A1. Trips are costed along one way and wear another

`PathWear.travelled` (the wear of every visit, gathering and raid, through `wearBetween`) and `firstStep` (the
direction a band's daily wear leans, `wearToward`) step greedily to whichever land neighbour is nearer as the crow
flies, the most worn first. They ignore the borders' effort: a path is worn straight across an unfordable river or up a
cliff that `tripKm` went round. Where no neighbour is nearer (a bay, a lake, a ridge in the way) the wear stops short.
Only the neighbours' meetings wear the way they were costed by (`wayTo` over `effortsFrom`).

**#762:** settled for trips and moves: the wear follows `findWay`'s hexes. **Still open:** `firstStep` and `wearToward`
(the lean of a band's daily wear toward the bands it deals with) still step by the straight line. Fix: lean toward the
first hex of `findWay`'s way (or the neighbours' `effortsFrom` reach, already searched for each band).

### A2. Grounds take in hexes that cannot be walked to

- A pack's footprint (`PackGround.footprint`) is every hex within a straight-line distance of its home, rings walked
  without regard to the borders between them, weighted by straight distance. The design said "by least effort over the
  map (`effortsFrom`)".
- A staying band's ground (`bandGround`, `Contacts.kt`) is its six neighbours, any that have ground.

A hex across a strait, a sheer cliff or an unfordable river can be in either. The pack then hunts and marks it
(`footGround`, `mark`), and both meet, fight and give way there (`contacts`, `engage`, `gaveWay`).

**#762:** not touched. **Fix:** both laid out by `effortsFrom` from home, within the range in km of easy going, each hex
weighted by its effort distance; one builder for both.

### A3. Groups that move by being placed

None of these asks whether the way exists, none costs the going, none wears a path:

- **Budding** (`findHome`, `PeopleSpread.kt`): a split-off band is set down in the hex beside, or up to `SPREAD_RINGS`
  rings out, by `n.place = ...`. The same in the yearly easing of crowding (`spreadYear`).
- **The beaten** (`scatterBeaten`): households go to the nearest kin within `VISIT_RINGS` rings and are set down at the
  host's place; the rest move to an empty hex beside.
- **Small bands joining kin** (`mergeAndPair`): within `VISIT_RINGS` rings, by ring, and the band is merged in where the
  host is.
- **Lone hunters pairing** (`mergeAndPair`): any two of a kind within a straight `MATE_SEARCH_KM`; one merges into the
  other.
- **Carrying the dead** (`HexLifeAndDeath.kt`, burial at the ancestors): within a straight `BODY_CARRY_KM`, the carrying
  costed by the straight km; no way asked for, no wear.

**#762:** settled for budding, the yearly easing, camp moves, discord, the beaten (moving and joining kin) and small bands
joining kin: all go by `moveCamp` / `joinOver`, and do not go where there is no way. **Still open:**
- **Carrying the dead** is still costed by the straight km to the ancestors, without a way or wear: a `trip` of the
  bearers (`WalkPaid.BY_CALLER`, the burial's days holding the walk), no site where there is no way.
- **Lone hunters pairing** still by a straight `MATE_SEARCH_KM`. Animals wear no paths, but the pair should be within
  the kind's reach over its crossings.
- **Candidates are chosen before the way is asked.** Kin to join (`mergeAndPair`) and the host for the beaten
  (`scatterBeaten`) are picked ring by ring, then walked to; if there is no way, the band does not join at all, though
  another kin band within reach might have had it, and the beaten households are left a band of their own in the hex they
  were driven from. Choose among those reachable (`effortsFrom` within the reach, then the ring order by effort).

### A4. Dispersers settle where their drift could not go

The wolves' and lions' dispersal pool drifts a hex at a time over the borders the kind can cross (`drift`, by
`hex.crossings`). But the ground a source's dispersers settle on (`dispersalAbout`, `ResidentMoving.kt`) is rings out to
the dispersal reach, weighted by straight distance, with no crossing asked. In one step a disperser can settle across a
strait or a mountain wall that its own drift would never cross.

**#762:** not touched (animals). **Fix:** the dispersal ground by `effortsFrom` with the kind's crossings (as `drift`
uses), weighted by effort distance.

### A5. A second, hidden kind of visit

Each week a band's ties to friendly bands "within a day or two's walk" are renewed by visits paid from its leisure
(`HexCulture.kt`, the ties' fading). That is a visit with no trip: judged by straight distance (`VISIT_RINGS` hexes), no
walk, no wear, and not a meeting at all, beside the neighbours' meetings (`neighboursMeet`), which renew ties too. Which
ties count as "beyond walking reach", and fade four times as fast, is judged by straight km against `CONTACT_KM`, which
everywhere else means km of easy going.

**#762:** not touched. **Fix:** ties are renewed by meetings only (they already are, `bandsMeet`), or the leisure
visits become `trip`s by the neighbours' rule. "Far" by `wayKm` (or the neighbours' `effortsFrom` reach, which is
already worked out for each band).

### A6. Smaller ones

- **Planned raids** (still open with #762) pick their target among known homes within a straight `RAID_RANGE_KM`, the
  safest first, then find the way and give up if there is none. A home beyond a sea strait can be chosen and the raid then
  dropped, though another target was within reach. Choose among the reachable.
- **People's pressure on the hunters** (`peopleAbout`) spreads a band's people over its hex and the six about, across
  impassable borders too.
- **Scouting parties** choose their destination by straight rings, and their way home by straight distance
  (`destination`, `partyPlace`); they walk by the planner, so the going itself is by the ground.
- **What bands know and tell** (`TOLD_RINGS`, `REMEMBERED_KM`, `homeReachKm`) is by straight distance. News carries
  across a strait as easily as along a coast. Lesser, since knowing is not going, but the routes and paths could weigh in.
- **Pulls in the planner's score** toward goals (season places, salt, raids, appointments) are straight-line ratios.
  Directions to steer by, not trips; acceptable, though a pull toward a place beyond a strait points the wrong way.

## B. One measure of reach, not three

The code measures "how far" three ways:

| unit | examples |
|---|---|
| km of easy going (`effortsFrom`, `findWay`) | `CONTACT_KM`, `OWN_GROUND_HEXES`, rites' `RITE_SEARCH_KM` |
| hex rings (`nextRing`) | `VISIT_RINGS`, `TOLD_RINGS`, `SPREAD_RINGS`, `KNOWN_AT_START`, `SCOUT_RINGS`, `SIGHT_RINGS`, the footprint |
| straight km (`hex.distanceKm`) | `RAID_RANGE_KM`, `BODY_CARRY_KM`, `MATE_SEARCH_KM`, `REMEMBERED_KM`, `NEIGHBOUR_RINGS` (by km), the footprint's range |

Rings and straight km are the same thing as the crow flies; both ignore the ground. Everything a group does in person
(going, meeting, settling, carrying) should be reckoned in km of easy going. Seeing and hearing (sight, news, fame) can
stay as the crow flies.

**#762** gives the one way (`findWay`); `effortsFrom` stays beside it as the search of everything within a reach. **Still
open:** a reach query cached for the step, `reachFrom(travel, ground band, from, mostKm)`, the hexes within reach with their
effort km and the way back, which `findWay`, the grounds (A2), the candidates (A3) and the ties (A5) all read. Today each
`findWay` and `wayKm` runs a fresh search, and a band asks several a step (visits, feasts, kin, rites, raids, its ground).

## C. The new mechanisms' abstractions

### C1. A group's ground is two loose fields with two meanings

`HexGroup.footprint` and `footWeights` are two parallel nullable arrays. For a pack they are the ground it hunts and
marks; for a band, the ground it is met on only (its hunting skips them by a `g.band == null` check in `huntGround`).
Whether a group "holds its ground" is the test `footprint != null && path.size == 1`, written out in five places
(`contacts`, `holdsHere`, the meetings' stays and contact indices), and a resident stand-in is recognised by `id < 0`.
`Contacts.kt` already has the right shape, `Sphere` (hexes and shares), but builds it from the footprint each step.

**Fix:** one value on the group for the step, its ground (hexes, weights, and what it is for), laid out by one builder
(A2); one predicate for holding ground; stand-ins marked as such, not by the sign of their id.

### C2. The meeting being settled is passed through globals

`GroupWorld.contested` and `contestDay` are set by the caller (`meetSettle`, `settleAt`) and read deep down by
`rivalsFight`, `enemiesClash` and `gaveWay`, and `contestDay` stands for "today" in `knownStrength` and `noteSeen`.
Nothing makes a caller set them: a meeting settled from a new place would give way over the last meeting's hexes.
`settle` and `engage` also take the parties' meeting indices and path points (`ak, ai, bk, bi, stop`) one by one.

**Fix:** an `Encounter` value: the two parties, where (hex, place), the contested hexes, how they came to meet
(`Intent`), the day, and the turning-back handle. `settle`, `engage`, `rivalsFight`, `enemiesClash` and `gaveWay` take
it; the globals go.

### C3. Two ways of noticing each other

The engagement draws who sees whom (`sightingsOf`: senses against hiding) and how a weaker side gets away (`escapes`, by
`slipChance`). A meeting settled by the kinds' own rules still uses the older one-draw `slipsAway`. They agree in spirit
but are two models. **Fix:** the plain path draws its sighting the engagement's way; `slipsAway` folds into
`sightingsOf` and `escapes`.

### C4. Strength is reckoned three ways

`strength(g)` (foragers x mass x ferocity) for engagements, enemies and raids; `rivalsMeet` by grown heads times home
advantage; `raidSides` by strength times the party share and ability. A rival fight between two packs and an engagement
between the same two can disagree about who is stronger. **Fix:** one `strengthAt(g, h, role)` (who is present, ground,
lair) that every fight reads; who is present per encounter (the open item from the presence work) belongs there.

### C5. Encodings to wrap

- `HexGroup.yielded` packs hex and day into a `Long` (`hex shl 32 or day`); the masks are written out in four places.
- `Band.seenStrength` and `seenDay` are two maps that must agree; `seenInputs` prunes both.
- `Scent` keeps two claimants in parallel maps (`marks2`, `markers2`), used by a footprint's marking (`claim`) only; a
  pack on the move still marks by the older single-claimant rule and can drop a second claimant.

**Fix:** small accessors for each (`Yields`, `Sightings`, one claim rule for all marking), keeping the saved arrays as
they are so saves stay compatible.

## D. Order of work proposed

0. **Merge #762.** It settles most of A1 and A3.
1. **B:** the reach query cached for the step, under `findWay`; `wearToward` by it (rest of A1).
2. **A2 with C1:** grounds by the reach query, one ground value on the group.
3. **A3, the rest:** carrying the dead as a trip; candidates (kin, hosts, raid targets) chosen among the reachable; lone
   hunters' pairing within the kind's reach.
4. **A4:** the dispersal ground by the kind's crossings.
5. **A5:** ties renewed by meetings only; "far" by the reach.
6. **C2:** the `Encounter` value; then C3 and C4, which it makes easy.
7. **C5** and the smaller A6 items as they are touched.

Each is A/B'd on the test worlds (`testWorld ab`) as usual; 1-5 change results (by how much depends on how often the
ground's barriers lie between hexes a few apart), 6-7 should not, and are checked line for line.

Overlap with the work under way in the other session: #762 settles most of A1 and A3; A2, A4, A5, B and C are not in it.
