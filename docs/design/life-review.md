# Life review: eating, deaths, skills and the ways networks

A follow-up to [consolidation-review.md](consolidation-review.md), whose items are all done. That review was about movement. This one reads the rest of a body's week: what it eats, how that feeds it, what kills it, how its skills change its work, and what its ways network reads and sets. It looks for the same kinds of problems:

- a rule decided in more than one place;
- the same quantity reckoned several ways;
- a mechanism that keeps a rule of its own where a shared one exists.

The areas are not equally frayed:

- **Wild animals** of every kind are in good shape. They live by one body model, whether in groups or as residents: fat, protein burnt, births by `breeding`, hazards by `Hazards`.
- **The ways networks** read in one place (`bandInputs`) and set in one place (`setWays`, one squash rule). Every way they set is read somewhere.
- **The food values** (`Food.energy`, `ForageWeek.worth` and `feeds`, `Store`) were recently worked over and hold together.
- **Kept animals** are the exception. Herds and dogs each live by rules of their own, which is item 1.

Ranked by how much each one can make the model disagree with itself.

## 1. Kept animals live by rules of their own

Wild animals have one body. Groups and residents alike carry fat days and protein burnt, and die of hunger by `Hazards.hunger` off them. They bear young by `breeding`, by their fat and their food, through the birth season. Cold, heat, thirst, snow and outbreaks kill them by `Hazards`, the young harder. The young born this step face its deaths only from birth (`newbornExposure`).

The animals a band keeps share none of this:

- **Herds** (`keepHerds`, `herdIncrease` in PeopleFarming) carry heads, young and tameness, and no fat or burnt body.
  - Hunger is a straight `0.5 × (1 − fed)` a year. A wild ewe starving burns its fat, then its body, and dies late and fast. A kept one dies a little every week it is short.
  - Births are `youngPerFemale × fed × season share`, not `breeding`.
  - The young die at the grown ones' rate.
  - The weather never kills them, and there is no newborn exposure.
- **Dogs** (`keepAnimals`) have no young, no season and no body. They die at a flat `DOG_DEATHS` and breed at `DOG_BIRTHS × fed`. They leave a camp that does not feed them at `0.5 × (1 − fed)`, "as fast as hunger would kill kept herds": tied to the herds' made-up rule rather than to any body.
  - What a band gives its dogs also mixes units. `eatStores` takes kg of the band's need and returns kg of weight, and both are added to the dogs' kg of want.

The comment in `liveWeek` says births "follow one rule for every kind, groups and residents alike". Kept stock is the one kind left out.

This matters most where herding does: PPNB and later. The test worlds hold about 5 herding bands, so today's A/Bs barely see it.

**Fix:** a kept herd of a kind is that kind's body at band level, by the shared rules:
- fat and protein burnt for each herd, as residents carry them;
- hunger by `Hazards.hunger`;
- births by `breeding`, through the kind's season;
- deaths by the residents' `hazards` at the pasture (hunger, cold, heat, thirst, snow, crowding, outbreaks), the young harder.

Straying, being taken by beasts and being slaughtered stay the herd's own, because they are about being kept. Dogs come under the same rule as a kept pack of their kind, with their leaving tied to that body's hunger.

This stays at band level, approximate, with no animal tracked on its own. It changes results, so it gets an A/B, ideally on a herding world.

## 2. The hazards are assembled three times, and the young's "harder" is a bare 1.5

The pieces are shared (`Hazards.hunger`, `cold`, `heat`, `thirstOver`, `calamities`, `crowdDisease`). But they are put together three times, in three vocabularies:

- **Groups:** `placeHazards` into a `HazardSink`, by `DeathCause`.
- **Residents:** `HexFauna.hazards` into an array, by `Danger`. Then `youngSum` and `youngFixed` weigh them for the young, and `bookDeaths` splits them again by `PlaceRecord.Cause`. That second split must agree with `youngSum` line for line, and only a reader keeps it so.
- **The residents' moves** (`ResidentMoving`) and **the death warning** (`foreseenHungerThirst`) each rebuild a part of it.

"The young die of it half as much again" is the literal `1.5f` 14 times across those four files, with no name and no source. One forgotten copy would make the young of one path die differently from another, and nothing would say so.

**Fix:** a cause's weight on the young in one place. For example `DeathCause.young` (1.5 for hunger, cold, heat, exposure and thirst; 1 for illness; and so on), documented once. Residents and the warning read it. `bookDeaths` takes its split from the same weights as `youngSum`, not from a copy.

Results should be unchanged, so it can be checked line for line.

## 3. Skills change work on two scales

`Band.skill(s)` is the band's level from the floor up to 1. `Band.lapse(s)` is how much more a start band's work it leaves undone. The skills doc fixes the convention as handling times "measured against a start band's level" ([survival-skills.md](survival-skills.md)), and most uses keep to it:
- cultivation, tending, hafting, lore, herbs, midwifery and tracking use `lapse`;
- gathering, small game and fishing are set for `START_CRAFT`.

About ten chores instead divide their days by the raw `skill()`:
- palisades, twice (`PeopleRaids`, `HexPeople`);
- shelters and thatch, twice (`PeopleWarmth`);
- each way of building a house, and the hall;
- weirs, three times (`PeopleWeirs`, `PlanDays`);
- wells.

There the day figures (`PALISADE_DAYS`, `WEIR_DAYS_PER_M`, `WELL_DAYS_PER_M`, `SHELTER_DAYS`, `HALL_DAYS`) are a master's. A practised band (about 0.6, so a skill of 0.72) takes 1.4 times the figure, and a band just come to the way (level 0, so a skill of 0.3) takes 3.3 times. The figures come from the record and from experiments with practised hands, so the scale is wrong for them. It also differs from one chore to the next.

The weirs' days are also reckoned in three places, and the palisade's in two.

**Fix:** every work's days by `lapse` against the way's reference level, and each figure documented as a practised band's. Where one work's days are reckoned twice, one function for them. This changes results (most chores get quicker for practised bands), so it gets an A/B.

## 4. A second way to eat, outside the meal

The week's meal (`Meal` in `eatTheWeek`) eats in `EAT_ORDER`. Protein feeds only so far (`leanLost`), the sodium need comes first, and the surplus is laid on as fat.

Food from kin bypasses it. In `visitKin` a hungry band is given food and eats it at once: `g.fed += eat / week`, and `eatStores` takes it off the stores in order. So protein feeds in full, and sodium is never counted. Kill feasts put their meat in the guests' stores, eaten at next week's meal, which is right.

**Fix:** the kin's gift goes into the band's stores, and the band eats it through the meal: next week, as feasts do, or this week through a short second `Meal` for the shortfall. Then there is one way to eat. A small change in results.

## 5. The year-ahead forecast leaves out what the week puts in

`YearAhead.year` (Foresight) shares the week's hunger rules (`Hazards.onFatOf`, `hunger`, `spentAfter`), which is right. Its background deaths are `backgroundMortality / 12` a month, though. The week's are `healed × backgroundMortality × tuning.humanOther`.

So the healers' share and the tuning are missing from what training judges a band by (`HexGroup.foreseen`). A band that learns herbs foresees nothing from it, and tuning `humanOther` moves the deaths but not the forecast.

**Fix:** the forecast's background deaths by the week's rule, through one function both read. A small change in what bands foresee.

## 6. Two leftovers of who is where

`WhoIsWhere` (consolidation item 4) missed two places that still build their own bands-by-hex map:
- `feastOnKills`, after the hunt;
- `mergeAndPair`, in demography.

Both should read `where.bandsIn` once each is shown to read at a moment `where` was built for: after the moves, as demography begins. This should leave results unchanged, so a line-for-line check covers it.

## Smaller things

- **HexPeople.kt is a grab-bag of constants:** salt and sodium, gifts and renown, plant learning, cooking, drying and keeping, beside the band's inputs. Each belongs with its mechanism (Meals, CultureMeetings, FoodStores). This is a move only.
- **Weirs and wells have no `timeWay`.** Need gates them, while the palisade, the other need-gated work, has `TIME_PALISADE`. Either is defensible, but they should be one way.

## Order

1. Items 2 and 6 first, together. They should leave results unchanged and are checked line for line.
2. Then items 3, 4 and 5 in one A/B, as small changes to how a band works and what it foresees.
3. Item 1 last and on its own. It is the biggest change and wants a herding world to judge it.
