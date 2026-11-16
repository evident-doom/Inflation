# Disease review: people's sicknesses, crowding disease and outbreaks

The sixth review covers sickness and plague:
- people's illnesses (`Illness`, `illnessWeek`, `passIllness`);
- the crowding disease of animals and people (`Hazards.crowdDisease`, `peopleSickness`);
- the outbreaks that start where a kind crowds and spread hex to hex (`HexFauna.outbreak`, `stepOutbreaks`).

Figures are from one year on m5-1, default era.

`Illness` is the most carefully built part. It has three sicknesses with their own seasons, bout lengths and deadliness, and immunity that wears off. Bands pass sicknesses on at meetings, and more so between strangers. The rates run in closed form over a step's days, so they hold at any step length. Several figures are sourced (Ram et al. 2008 for the flux; Gurven and Kaplan 2007 for the share of deaths). The frays are around it: other rules that do part of its job, and the animals' disease, which does a job that isn't disease's.

## 1. People fall sick by four rules

A band's sickness comes from four places:
- **`Illness`:** the flux, marsh fever and the cough, each with immunity and contagion at meetings.
- **`peopleSickness`:** a crowding sickness, `PEOPLE_SICK × crowd²` a year with no immunity, booked as "disease". Hunger multiplies it (`famineIllness`).
- **Outbreaks:** the animals' plague rule run for people at `PEOPLE_OUTBREAK` (0.15) of the rate. It starts where people crowd, spreads to neighbouring hexes where any band is, kills `OUTBREAK_KILL` a tick while it rages, and leaves no one immune. It's booked as "outbreak".
- **Camp fouling:** booked as its own cause.

The cough is already the epidemic that runs between crowded bands: it breaks out at crowd², it's caught at meetings, and those who live through it are spared for 20 years. People's outbreaks are a second epidemic beside it with none of that. They pass by being in the next hex rather than by meeting, and they leave no one spared. The crowding sickness is a third rule rising with crowd², one that the flux's own crowd term (`0.3 × crowd`) already partly covers.

Among children's deaths on m5-1, "disease" is 2% and "outbreak" 2%, against the cough's 10% and the flux's 10%. They are small, but they are separate figures fitted to the same record twice ("fitted, not sourced", crowd734).

**Fix:** people's sickness goes through `Illness` alone:
- People's outbreaks stop; the cough is their epidemic.
- The crowding sickness becomes the flux's crowd term, or a fourth `Illness` if it stands for something the flux doesn't (crowd fevers, say), with its own bout and immunity.
- `famineIllness` scales `Illness`'s deadliness instead, so hunger makes every sickness worse, not one.

Then refit the cough and flux to the record they were fitted to. This changes results, so it needs an A/B, read against the record of deaths by cause (illness some 70% of forager deaths, Gurven and Kaplan 2007).

## 2. "Disease" is what limits the animals, before food

Resident deaths on m5-1 in a year:

| cause | deaths |
|---|---:|
| disease (crowding and outbreaks) | 7.0 million |
| age | 6.3 million |
| hunger | 1.9 million |
| thirst | 0.7 million |
| hunted | 0.4 million |

The crowding disease is `DISEASE × crowd² × (1 + 4 × (1 − fed))` a year, with crowd as heads against 4× the kind's usual density. It rises with the square of numbers whatever the food. At twice the usual density it kills 7.5% a year of well-fed animals; at four times, 30%. So it is the animals' density limit, and it binds before food does: a kind is held near its usual density by disease even where the grass would carry more. Hunger enters through the `fed` term, at most five times as much in a starving crowd, but still through disease rather than by starving.

That runs against the record for big grazers, which are mostly held by food. Winter and dry-season starvation, falling hardest on the young and old, is the usual limit (Sinclair's Serengeti wildebeest, the Soay sheep of St Kilda, the red deer of Rum; from memory, to check). Disease is mostly a plague now and then (rinderpest), not a steady tax on crowding. It is also the point you made about farming: food has to limit numbers, and here a fixed density does.

The same holds for people. `peopleSickness` rises with the square of crowding against a fixed `PEOPLE_FULL_KM2` (0.5 a km²), whatever the larder.

**Fix,** in two steps, each an A/B:
1. Find what holds numbers where they are now. Weaken `DISEASE` by half and by nine tenths in turn, and watch whether hunger takes over, numbers settle where food sets them, or they run away. That shows whether food can carry the limit at all as the feeding rules stand.
2. If it can, the crowding disease shrinks to what crowding itself brings: contact disease, and parasites for kinds that crowd (a herd's worms), with `fed` its main driver. Hunger then does the limiting it is meant to.

This is the larger task you named, food limiting populations. It is listed here because disease is where the limit sits now.

## 3. Outbreaks keep to one kind, and spare no one

An outbreak is kept per kind and hex, and passes only to hexes where that kind lives. But the plagues it stands for cross kinds:
- rinderpest ran through cattle, buffalo, wildebeest, giraffe and warthog;
- canine distemper runs through wolves, jackals, hyenas and dogs;
- anthrax kills any grazer.

And kept herds never carry one. They die of an outbreak among their wild kind where they graze (`fauna.hazards` reads `outbreakDeaths`), but they neither start one nor pass one on (`groupHeads` counts groups, not herds). Herds and wild kin passing plagues back and forth was one of keeping's costs.

Survivors are spared nothing either. An outbreak burns out in a few weeks (`OUTBREAK_KEEP` 0.85 a tick), and if the crowding holds, one can break out again at once. A plague that left its survivors immune (rinderpest gave lifelong immunity) would come in waves years apart, as the record's die-offs do.

**Fix:**
1. A kind's outbreak passes to the kinds that share its plagues. A `Species.plagueKin` set (bovids together, canids together), read as `keepsOff` is, sets who shares one; the outbreak's front passes into a hex where any of them are, and kills each.
2. Kept herds count with their kind where they graze, both to start an outbreak and to pass one on.
3. Each kind and hex keeps a spared share after an outbreak, fading over some years. A new outbreak strikes only the unspared, and starts only where enough are unspared.

Item 3 is the one that changes the outbreaks' rhythm most; take it first, then 1 and 2. Each needs an A/B.

## Smaller things

- **People's crowding has its own scale:** `peopleCrowding` reads people per km² round a camp against a fixed `PEOPLE_FULL_KM2`, while animals read their crowding against their kind's usual density. It's one more fixed number in people's limit (item 2).
- **Fitted figures:** `COUGH_BREAKS`, `PEOPLE_SICK`, `PEOPLE_OUTBREAK` and `HYGIENE_GUARD` are fitted to the deaths record, not sourced. Item 1 removes two of them.
- **Outbreak starts:** for residents the start is drawn by a hash of place and week, for groups by the group's stream. Both are fine; it is noted only because the two are drawn differently.

## Order

1. Item 2's first step, a probe with no fix: how numbers settle with the crowding disease weakened. It decides how much of items 1 and 2 to do, and is the start of food limiting populations.
2. Item 1: people's sickness through `Illness` alone. One A/B.
3. Item 3: outbreaks that spare survivors, then cross kinds and herds. One A/B each.
