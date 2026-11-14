# Consolidation review: what still runs on more than one rule

A follow-up to [coherence-review.md](coherence-review.md), whose items are all done. The same kinds of problems:

- a rule decided in more than one place;
- the same quantity reckoned several ways;
- state passed through globals or loose parallel fields;
- going reckoned as the crow flies where the ground matters.

Ranked by how much each one can make the model disagree with itself.

## 1. Who is present forgets who is away

`presentAt` counts a band met at its camp as all its able grown, and as a party of `BAND_PARTY` anywhere else. Several mechanisms send people away for days or weeks, and none of them tells presence:

- scouting parties (`ScoutingParty`) do split off as groups of their own, so they are counted right;
- task trips (`taskTrips`) send three people for one to four days;
- raiders (`raidParty`, half the able) are away for the raid's walk;
- visitors (`VISITORS`) go to the host's camp;
- bearers (`BEARERS`) carry the dead;
- herders stay at a summer pasture all season (`placeHerd`).

So a band whose herders are 60 km off still defends its camp with everyone, and the herders at the pasture are met by nobody: a beast at the pasture meets the herd but no people.

**Fix, kept at band level** (Olli, 14 Nov 2026: the model keeps no exact count of a band's members; below the band, approximate):
- a band met at its camp is its able grown times its home share ([BAND_MOBILE_HOME], [BAND_FIXED_HOME]), the share of its time its ground already gives the camp. That stands for its usual parties out, with no record of who went where;
- where people should be met apart for weeks, as herders at a summer camp, the band splits in two and joins up again, as scouting parties do, rather than keeping track of the ones away.

## 2. Walking: one quantity, eight formulas and two models

The person-days of walking `km` there and back, `2 × km / (WALK_KM_PER_HOUR × WORK_HOURS)`, is written out on its own in eight places:

- `visitDays`;
- `walkOver`;
- the burial's carry (`mournDead`);
- `riteDays`;
- gathering's carrying (`GroupFood`);
- carrion fetching;
- firewood (`WornGround`);
- the forage offers' day's walk.

Some divide by a load and some don't.

Beside that is a second model of how far people go: the species' `dailyTravelKm` / `maxDailyTravelKm × SHIFT_DAYS`. The planner's reach and the scouts' range use it.

The walk's days are paid into separate ledgers:

- `raidWalkOwed`, also used now for herders driving their herd, though named for raids;
- `visitOwed`;
- `burialOwed` and `riteOwed`;
- `WalkPaid.BY_CALLER`, where each caller does its own spending.

**Fix:**
- one `walkDays(people, km, loadKg?)`;
- people's reach in a step from the same walking speed;
- one owed-days ledger keyed by purpose (raid, visit, rite, burial, herding, task), which the week's plan reads as it reads the others.

## 3. Distance scales chosen one by one

The people's distances are separate constants, some km of going, some rings, some straight. A day's walk is 24 km by `WALK_KM_PER_HOUR × WORK_HOURS`, and the constants sit at:

- about 24–25 km: `BODY_CARRY_KM`, `kinReachKm` (2.5 hexes);
- 40–60 km:
  - `TASK_KM` 40;
  - `toldKm` 45;
  - `CONTACT_KM` 50;
  - `MATE_SEARCH_KM` 50;
  - `RAID_RANGE_KM` 60;
  - `SUMMER_PASTURE_KM` 60;
- 100 km: `HOMES_KM`, `REMEMBERED_KM`;
- 150 km: `ROUTE_TOLD_KM`, `GOAL_GOING_KM`;
- 400 km: `RITE_SEARCH_KM`;
- in rings: `SPREAD_RINGS` 3, `VISIT_RINGS` 2.

None is wrong by itself, but nothing keeps them in step: a band tells of country 45 km about, knows homes 100 km off, and visits within 50.

**Fix:** a few named scales from the walking speed (a day out and back, two days, a week, a season's round), each constant written as one of them with its reason, the rings converted to km of going.

## 4. Who is where: seven indexes, built at different times

- `bandIndex` and `herdIndex` in `indexBands`;
- `bandHex`, `groupById`, `followersByHex` at the end of the step (`HexLifeAndDeath`);
- `packsAt` in `PackGame`;
- `homePeople` in `Structures`;
- the meetings' own `byHex`.

Each is right at a different moment, and a mechanism that reads one after groups have moved sees them where they were. `bandHex` is also saved, though rebuilt each step.

**Fix:** one step index of groups by hex and by id, rebuilt at named points (start of step, after moves), the rest as views of it; the saved copy dropped.

## 5. What a band knows of another: nine maps by id

A band keeps nine maps keyed by the other band's id, each pruned by its own rule or not at all:

- `ties`, `lastMet`;
- `seenStrength`, `seenDay`;
- `knownHomes`, `knownLineage`, `homeSeen`, `homeOff`;
- `favours`.

Two of them have problems:
- **`lastMet` is never pruned:** it grows with every band ever met, dead ones too, and is saved.
- **The homes' four maps:** they must be removed together, which is written out twice by hand.

**Fix:** one `Acquaintance` per known band (tie, last met, last seen and strength, home and how sure, lineage, favours), forgotten as a whole when nothing in it is worth keeping. The saved maps can be read into it for old saves.

## 6. Where straight distance still steers

Left after the coherence review, each about where a group goes or what it counts as its own:

- **Pack ground:** whether a pack is "beyond its ground" (`GroupPlanner`, `beyond`) and which hexes a pack on the move marks (`mark`) are by a radius from home, though its ground is laid by the going (`layGround`). Being on its ground should mean being in its footprint, or within its going.
- **Going home:** a pack goes home within a long step if home is within its travel as the crow flies (`PackGround`).
- **Salt:** the nearest salt a band knows (`Bands.saltKnown`) is chosen as the crow flies, then walked by the ground: the one across a strait can win.
- **Budding:** a budding band looks for its new home by rings (`SPREAD_RINGS`), and a beaten band scatters "farthest from the raiders" as the crow flies (`scatterBeaten`).
- **Telling and burying:**
  - routes are told within `ROUTE_TOLD_KM` straight, beside country told by the going (`toldKm`);
  - two homes are "apart" for the burial customs beyond `BODY_CARRY_KM` straight (`homesApart`).

## 7. Seasons by the calendar

Seasons are fixed months, everywhere at once:
- births, honey, eggs and snails;
- the shoals;
- hares and owls;
- the hibernators' autumn;
- the new summer pasture (`SUMMER_MONTHS`).

A place's own weather and height do not move them. Elsewhere the model reads the weather: `dryMonth` for salt, snow, the growing grass. Memory's seasons are quarters (`seasonOf`), and `world.Season` is a third reckoning.

**Fix:** a place's season from its weather (warmth for growth and births, dryness for salt and pasture), with the calendar months as the fallback for things with no weather of their own.

## 8. The stand-ins' meetings keep their own copy of the clash

The resident stand-ins' bulk draw (`standInsMeetDraw`) settles many meetings at once by its own lines:

- a slip chance drawn whatever the weaker would choose;
- kills only past twice the strength;
- no will to fight;
- the stronger eats half of what it kills.

The same meeting by the kinds' own rules (`plainOutcome`, `enemiesClash`) gives way by the will to fight and leaves the dead as carrion. A beast meets a stand-in by one rule and a group of the same kind by another.

**Fix:** the bulk draw as `plainOutcome`'s numbers drawn `times` over (binomial by outcome), with the kill left as the plain path leaves it.

## 9. Small ones

- **`meetMonth`** is set by the meetings and read by `youngHidden`, the last of the meeting globals: it belongs with `asMeetings` or on the `Encounter`.
- **Party sizes:** a raid half its able, task parties 3, visitors 4, bearers 4, a band met out 4, scouts 15%. Each is sourced alone; they could sit together, as estimates of one kind, without tracking anyone.

## Order proposed

1. Item 8, then item 1: they change who fights and who is met, so each is its own A/B.
2. Items 2 and 3 together: one walking model and its scales; results change where the constants move.
3. Items 4 and 5 together: indexes and acquaintances, which should not change results (checked line for line), except that pruning `lastMet` shrinks saves.
4. Item 6 as one bundle; item 7 on its own: seasons by the weather moves breeding.
5. Item 9 as it is touched.
