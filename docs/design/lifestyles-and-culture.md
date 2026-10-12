# Lifestyles and culture

Proposal, 2026-10-12. Nothing here is built.

## Lifestyles are outcomes, not types

Foraging, following herds, winter and summer camps, seasonal gatherings, settled villages and herding are not settings to pick. They are what a band ends up doing week after week, given:
- **what it can choose:** where to camp, how long to stay, who goes where, what to keep;
- **what its land offers:** where food is, when, and whether it can be stored;
- **what it knows and has:** its skills, stores, tools, traditions and neighbours.

The model should make those choices possible and leave the pattern to emerge. Ethnography has names for what tends to come out (from memory, unchecked):
- **Foragers** (Binford's "residential mobility") move the whole camp to the food, often, with little storage.
- **Collectors** ("logistical mobility") stay at a base and send task groups out for days. Hunters follow herds or go to a lake or a pass; a party goes for flint. They store, and use winter and summer camps.
- **Herd followers** track one migratory species around its yearly round. That needs herds that migrate, which the season memory already gives.
- **Aggregation and dispersal.** Bands come together where food is briefly plentiful (a fish run, an acorn mast, a gazelle drive) for exchange, marriage and ritual, and split up again in lean times. Göbekli Tepe, built in this region by hunter-gatherers about 11,600 years ago, is the famous case.
- **Sedentism** comes with rich, storable, local food: acorns, wild cereals, a lake. The Natufian villages are the example. Settling is the step before farming, not after it.
- **Husbandry** grows out of taming the bold (already modelled), keeping captured young, and moving herds or penning them.

## Choices a band needs, beyond today's

1. **Stay or move camp,** with costs. Moving costs a few days and whatever stores and gear can't be carried. Staying wears out the land nearby and fouls the camp (parasites, waste).
2. **Task groups.** Some adults go out for days (to hunt, collect flint or obsidian, fish, harvest) while the rest stay. This is what lets a band live at a base and still use far resources.
3. **Fission and fusion.** Split into family groups in the lean season and come together at rich places. The pull of a gathering is food plus the social goods: partners, news, exchange.
4. **Storage and caches,** which tie a band to the places where its food is stored.
5. **Keeping animals.** Take young alive and raise them, keep bold animals near camp. Later, guide and pen herds.
6. **Tending plants.** Keep stands of wild cereals and pulses, weed them, sow seed near camp. Later, cultivate.

## Culture as traditions

A **tradition** is a practice or belief a band holds. It spreads like knowledge: by talk, by living together, and by imitating successful neighbours. It changes slowly, and each has concrete effects in the model. Candidates with some evidence for the late Stone Age and Natufian (from memory, to be checked):

| Tradition | What it does in the model |
|---|---|
| **Burial of the dead**, cemeteries (Natufian) | ties a band to a place: lowers its willingness to move far and strengthens its claim to the ground; a sign of settling |
| **Ancestors and the dead** | stronger memory of places and routes passed down; respect for kin bands with shared dead |
| **Sky lore: sun, moon, stars** | better timekeeping; seasonal rounds and gatherings arrive on time |
| **Nature and animal spirits, totems, taboos** | rules on hunting: no killing of a species, or of females and young, or in a season; can work as conservation |
| **Oral tradition: songs, stories, genealogies** | knowledge (*K*) decays far more slowly across generations; skills are relearned faster after a gap |
| **Dance, song, shared feasts** | cohesion: larger bands before they split, better cooperation in drives, more pull to gatherings |
| **Monuments, mounds, ritual centres** | fixed gathering places, built with labour that only big aggregations have |
| **Medicine** (herbs, setting bones, wound care) | lower deaths from wounds, illness and childbirth; costs plant knowledge |
| **Care of the injured and sick** | hunters wounded by prey or predators survive more often; the band carries the cost |
| **Exchange and gift partners** | goods travel far (obsidian, shells); food sharing between bands in bad years, as insurance |
| **Ornament and marks of belonging** (beads, ochre) | who counts as "us": shapes which bands share and which avoid or fight |

A tradition is not good or bad in itself. Its value depends on the band's situation. Taboos that protect a declining species help a band that depends on it and cost one that doesn't. That is why traditions are worth modelling: they give different bands different strategies that compete.

## Learning: cultural evolution rather than training

For people, the natural learning mechanism is cultural evolution, not training a network offline:
- **Imitation.** Bands copy the practices and choices of neighbours that do visibly well: more children, fuller stores, better hunts.
- **Innovation.** A band tries a variation now and then, with low odds, raised by need and by exposure.
- **Inheritance.** Practices pass to both halves when a band splits, with some drift.
- **Selection.** Bands that do better grow and split more, so their ways spread. This is the "evolution inside the world" in the learned-agents plan, and it suits people better than wolves, because there are many bands and imitation is fast.

A band's behaviour can then be a small set of parameters (how far to range, when to move, what to store, which traditions to keep), or later a small network, inherited and copied. No offline trainer is needed to start, and it scales with the simulation.

Offline neuroevolution of animal policies (the wolves) is still worth doing later. It is not on the critical path for people.

## Suggested order

1. **The band's decision layer:** stay or move camp with real costs; task groups for far resources; fission and fusion with gathering places. Lifestyles start to emerge here.
2. **Tools and materials:** containers, sickles and grinding stones (storage and bulk plant food, which also lifts population density), weapons, clothing, flint and obsidian sources, and exchange.
3. **Traditions** as data on bands, spreading by contact and imitation, each with the effects above.
4. **Cultural evolution:** imitation of successful neighbours, innovation, inheritance on a split.
5. **Keeping animals and tending plants,** on top of boldness and sedentism.
6. **Language and meetings** as the channel through which steps 3 and 4 travel, with news and rules from the skills design.
