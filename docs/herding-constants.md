# Herding constants: reasoning and sources

Moved from the doc comment of the constants in `PeopleFarming.kt` (`CAPTURE` to `HERD_MASS_EXP`), which now carry one line each. Source names and `[links]` are as they stood in the code.

Herding: chance a young animal caught in the hunt is kept and raised instead of eaten (times its
kind's tameability); yearly share that run off, [HERD_RUN_OFF] times the square of their wildness ([straying]; the square an
estimate, so that the wilder stray far more), their wildness reckoned from their tameness times their kind's ease on foot, the
worse of Diamond's panic and social-structure criteria, against a goat's ([HERD_EASE] 0.6, an estimate: a kind that panics or
keeps no herd, as an ostrich or a deer, is not kept on foot however tame), so that a goat herd as tame as one at world
start (0.8) loses 2% a year to straying (an estimate: no record gives half-tame herds' straying; the wild-type stock of the first
herds, as at Ganj Dareh some 10,000 years ago, still bred and paid its herders, Daly et al. 2021, from the press release).
Tame herded flocks stray not at all, as [straying] has it: flock records of herded sheep and goats count no strays and
0-1% of their outflow to predators, Assen and Aklilu 2012, mixed farms in Tigray, not nomads; so a move costs a herd only
its walk. Beasts of prey take their share in the hunt ([raidHerd]), not here: the goats of central Mali, 1978-83, whose
adults died 12.7% a year, slightly more than 17% of the deaths "lost" or taken by predators, mainly dogs (FAO x5531e,
ch. 13), are a check on the two together. [HERD_FIGHTING], the same scale for deaths from fighting ([fighting]);
yearly growth of tameness. And the head a herder keeps on foot ([headsAHerder]): 200 goats of [GOAT_KG]; Iranian nomads' flocks of
200-600 head go with one to three shepherds (Encyclopaedia Iranica, "Cupan"). Bigger beasts fewer, as their weight to the
power [HERD_MASS_EXP], an estimate between the goats and camel herds of 50-60 head kept by young men (ODI Pastoral
Development Network paper 5337; the herders to a herd not given). Young caught and raised by hand come as tame as a
herd started at world start ([RAISED_TAME], an estimate), whatever their kind: young of wild hoofed animals reared by people take them as
their own and stay by them (imprinting; Price 1984, "Behavioral aspects of animal domestication", from memory), long
before their kind changes in body. Kinds also differ in how they breed and how they get on kept without pens or incubators,
by the other two of Diamond's criteria (Guns, Germs, and Steel, ch. 9, from memory): their young as their captive breeding
against a goat's ([HERD_BREEDS] 0.7, [herdMothers]), their deaths from fighting by their disposition against a goat's
([HERD_TEMPER] 0.6, [fighting]); the scales and the square estimates, the criteria the catalogue's.

## Unwatched herds (herd drift, 2026-10-31)

The part of a herd its band has no herder days for this week is not culled. It grazes by the camp unwatched, and each week
the share [unherdedStrays] of it does not come home: the square of the herd's wildness, as [straying]'s, but weekly where
[straying] is yearly. A fully tame herd of a kind as easy on foot as a goat loses none (village goats in south-west Nigeria roam free in
herds of 7-9, and strays are not among their main exits: ILRI, "Composition of village goat herds in southwest Nigeria",
abstract only); such a herd as tame as one at world start (0.8) loses 4% of its unwatched part a week; a wild one all of it. The
herded part strays as before ([straying]): a herder cannot hold a kind that panics. No record gives how half-tame herds come
home; the weekly scale is an estimate. Field herders walk with the herd all day, 4.5-19 km (Zampaligré and Schlecht 2012,
Burkina Faso; Coppock 1988, Kenya; Schlecht 2006, Niger), which at 10 km hexes is about the camp's own place, where the
herd grazed: herder days buy no extra reach in this model. A kept herd grazes the circle half its day's going across round
the camp ([herdForage], as [HexFauna] spreads a band of residents), no more than the place; before, it grazed the whole
place, about 87 km² a hex, so forage never bound a kept herd (fed 0.96-0.98 in the herd drift A/B). Design and sources:
https://claude.ai/artifact/FHCFHVZDKNusAE6nUDBtA9
