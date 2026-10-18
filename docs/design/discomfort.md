# Discomfort: what a group feels, and what it does about it

Status (2026-10-18): steps 1 to 3 are built (step 1 was A/B'd: no effect). Step 4's marsh fever is built; its other parts are proposals. Step 5 is not built as written: bands and herds have a temperament (`Temperament.kt`), but on four axes (boldness, exploration, activity, sociability), not as a tolerance for each source.

## Why

Biting insects cost animals feeding time and people working time, but no group *felt* them: nothing
moved to a windy ridge or away from a marsh camp ahead of the loss. The same holds more widely. Each
hardship (cold, heat, wet, thirst, hunger, threat, flies) is charged as a real loss somewhere. What
is missing is the felt side, which arrives before the loss and is what an animal or a band acts on.

In this model discomfort has no worth of its own. Learned choices are judged only by the group's
numbers, and the hand-written choices should approximate the same thing. So discomfort is the
**sense of a cost**: it should be as strong as the cost it warns of, and come early enough to act
on. A source the model does not yet charge as a real loss should not be felt at all. Otherwise
groups would flee something that costs them nothing, and learned groups would unlearn it.

## Not a sum

Drives compete. The most pressing one decides what a group does this week (thirst sends it to
water, whatever the flies), and the rest shape *where* it goes and *how* it does it. That is the
classic picture of motivation (one behaviour at a time, chosen by the strongest drive, modulated by
the others). So:

- **Whether to move** (restlessness) is set by the most pressing source, each against its own
  threshold. The threshold is where that source's real loss begins, not a shared constant.
- **Where to go**: each source weighs on the places a group looks at in proportion to what it would
  cost there. This is the existing place score, with each term in a cost unit.
- **What the learned movers see**: each source as its own input. A total hides which drive is
  pressing.

## Sources

| Source | Real loss today (code) | Felt when | Choices that ease it |
|---|---|---|---|
| Cold | extra food burned (`coldNeed`: `COLD_COST` 0.1 of need per 10 °C beyond the coat, in `dailyNeedKg`), animal deaths (`discomfort`×0.02 a week), people's exposure deaths (`Band.exposure`) | below the kind's coat range; people through clothes, fire, shelter (`Band.feels`) | shelter, lower or sheltered ground, fire, clothes, huddling |
| Heat | the same ×0.02 term | above the kind's range | shade, water, high ground, resting by day |
| Wet | **none yet** | not felt until charged | shelter, a dry camp |
| Thirst | days without water (`g.dry`), deaths past `daysWithoutWater` | `g.dry` > half the limit (exists) | go to water, dig, carry |
| Hunger | `HUNGER_RATE` below `HUNGER_FROM`, fat used up | `fed` below the kind's level (exists) | forage, move, stores |
| Threat | predation, raids | hunter density and the landscape of fear (exists, score only) | cover, company, vigilance, leave |
| Biting insects | lost feeding time (`HARASS_FEED`), slower work (`HARASS_TIME`) | **step 1** | wind, high ground, open water; people: smudge fires |

Hunger, thirst and threat already have their felt side. Cold and heat have one, but in °C/10, which
is not a cost unit. Biting insects had none until step 1. Wet has neither.

## Steps

1. **Built.** The flies are felt through `HexGroups.bitten`, the insects' real cost as a share: feeding
   time for plant-eaters (`HARASS_FEED`·h), working time for people
   (`HARASS_TIME`·h / (1 + `HARASS_TIME`·h)), and 0 for hunters (they are not charged). There is no
   separate `felt` any more. `HexGroups.discomfort` is the temperature alone, and the flies reach the
   place score inside `HexGroups.drain` (step 2). Mover inputs `f[4]` and `f[5]` carry discomfort plus
   drain (`f[5]` with the forecast weather). Restlessness from the weather is temperature only: herds
   when `discomfort` passes 0.5, bands when the cold or heat felt through their clothes and fire
   passes 0.5 (`Lifeways.kt`). The flies make a group restless only through step 3's relief test.
   The death term stays temperature only. Step cost: one array read per candidate place.
   - Limit: at worst the flies cost people a third of their time and plant-eaters 0.4 of their
     feeding. They are not part of the 0.5 test, so only step 3's relief test lets them move a group.
2. **Built: drains in the going's unit.** Cold already costs food (`coldNeed`, about 1% of need
   per °C beyond the coat, in line with Scholander-curve slopes for mid-size mammals). So the
   place score now subtracts `HexGroups.drain`, the cold's extra food plus the flies' cost, as a
   share of the week's need, the same unit and weight as the going (`WALK_COST`). The old
   `0.5 × °C/10` term stays beside it as the felt side of the cold and heat deaths (the ×0.02 term),
   which is a real loss of a different kind. `f[4]` and `f[5]` carry both. No deaths or needs
   changed.
3. **Built: restless when relief is a step away.** Every group (herds, packs, bands) is restless
   when the drain where it stands is worse than at the best next hex by more than going one hex
   costs (`WALK_COST` × hex km / days). This is a threshold from the cost of moving, not a new
   constant, and it holds only where relief exists: a lower valley in the cold, a windy ridge or
   dry ground from the flies. The per-role reasons (hunger, thirst, astray, the round, and cold or
   heat above 0.5) stay. Cost: six extra place lookups per group per week.
   - First A/B (1691d9d): treating relief as restlessness also widened the group's reach to its
     full week's travel, and other pulls then carried it far. On full-1, wild horses drowned at
     crossings about twice as often, were hunted 15-45% more and bore 6-15% fewer young
     (hartebeest likewise: more hunted, fewer births). So relief now only makes a group weigh its
     places that week, with its usual reach. Kinds whose usual two days' going is under a hex
     (3-4 km a day) can't take the relief.
   - With a hex of 10 km, the threshold is 1.6% of the week's need: about 1.6 °C of cold
     difference to the next hex, or a small fly difference. Expect many more groups to weigh
     their places each week. That is the large behaviour change this step is for, and the
     A/B has to show whether it costs groups or helps them.
4. **Missing real losses, each with its easing choice**, only if wanted:
   - flies: lost sleep at camp (a work-capacity cost), eased by a smudge fire at a fuel and time cost;
   - **built:** marsh fever driven by the mosquitoes actually at camp (biting / (biting + `HARASS_HALF`),
     still only above 20 °C), instead of the wetland share. Fever now follows floods and sodden
     ground after rain as well as marsh. The choice that eases it is where to camp. Smoke was left
     out because I found no grounded figure for how much it spares;
   - wet: rain on a band without shelter adds to cold exposure.
5. **Tolerance by temperament**: how strongly a band feels each source (not what it costs) varies by
   inherited temperament, so some bands hold out where others move.

Each step goes through an A/B on full-1..3 from saved state with weather replicates.

## Open questions

- Step 3 changes every species' movement, not only people's. Should it be split, people first?
- Step 2 needs a per-kind lower critical temperature and slope. Is the `comfortC` range good enough
  to derive them, or should they be catalogue values?
