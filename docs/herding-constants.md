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
