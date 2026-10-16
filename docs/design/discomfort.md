# Discomfort: what a group feels, and what it does about it

Status: proposal (2026-10-16). Step 1 is built; the rest is for discussion.

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
| Cold | animal deaths (`discomfort`×0.02), people's exposure deaths (`Band.exposure`) | below the kind's coat range; people through clothes, fire, shelter (`Band.feels`) | shelter, lower or sheltered ground, fire, clothes, huddling |
| Heat | the same ×0.02 term | above the kind's range | shade, water, high ground, resting by day |
| Wet | **none yet** | not felt until charged | shelter, a dry camp |
| Thirst | days without water (`g.dry`), deaths past `daysWithoutWater` | `g.dry` > half the limit (exists) | go to water, dig, carry |
| Hunger | `HUNGER_RATE` below `HUNGER_FROM`, fat used up | `fed` below the kind's level (exists) | forage, move, stores |
| Threat | predation, raids | hunter density and the landscape of fear (exists, score only) | cover, company, vigilance, leave |
| Biting insects | lost feeding time (`HARASS_FEED`), slower work (`HARASS_TIME`) | **step 1** | wind, high ground, open water; people: smudge fires |

Hunger, thirst and threat already have their felt side. Cold and heat have one, but in °C/10, which
is not a cost unit. Biting insects had none until step 1. Wet has neither.

## Steps

1. **Built.** `HexGroups.felt` = temperature discomfort + `bitten`, where `bitten` is the insects'
   real cost as a share: feeding time for plant-eaters (`HARASS_FEED`·h), working time for people
   (`HARASS_TIME`·h / (1 + `HARASS_TIME`·h)), and 0 for hunters (they are not charged). It feeds
   the place score, herd restlessness and mover input `f[4]` (`f[5]` adds the flies to the forecast
   weather). Bands are now also restless when cold or heat, felt through their clothes and fire,
   plus the flies, passes 0.5, the same threshold herds use. The death term stays temperature only.
   No new inputs. Step cost: one array read per candidate place.
   - Limit: at worst the flies cost people a third of their time and plant-eaters 0.4 of their
     feeding, so on their own they never make a group restless. Combined with heat they can.
2. **Cold and heat in cost units.** Replace °C/10 with the extra energy a group must burn (or water
   lose) as a share of its daily need. The slope is per kind, from metabolic rate below the lower
   critical temperature (Scholander), with people through `Band.feels`. The ×0.02 death term would
   then follow from energy rather than being a separate fudge.
3. **Restlessness by the most pressing source.** Replace the per-role `restless` lists with one rule
   for all groups: restless when any source passes its own threshold (hunger and thirst as now; cold
   and heat where exposure or energy loss begins; flies where their cost passes what moving costs).
   This is where the large change in band behaviour would come from.
4. **Missing real losses, each with its easing choice**, only if wanted:
   - flies: lost sleep at camp (a work-capacity cost), eased by a smudge fire at a fuel and time cost;
   - marsh fever driven by the mosquitoes actually at camp, instead of the wetland share and >20 °C;
   - wet: rain on a band without shelter adds to cold exposure.
5. **Tolerance by temperament**: how strongly a band feels each source (not what it costs) varies by
   inherited temperament, so some bands hold out where others move.

Each step goes through an A/B on full-1..3 from saved state with weather replicates.

## Open questions

- Step 3 changes every species' movement, not only people's. Should it be split, people first?
- Step 2 needs a per-kind lower critical temperature and slope. Is the `comfortC` range good enough
  to derive them, or should they be catalogue values?
