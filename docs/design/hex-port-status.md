# Hex model: what is ported from the 1 km model

Status on 13 Oct 2026, after the ground-up port; rows corrected on 18 Oct 2026. For each feature of the 1 km (grid) model: **yes** ported, **partly** ported
(what is missing), **no** not yet. The hex model runs from the hex viewer, the hex checks and the
hex learning tools. (The 1 km model's `Simulation` has since been removed; the hex model is the only one.)

## Weather and climate

| Feature | Hex |
|---|---|
| Stochastic weather around the normals | yes |
| Five-year and decadal swings | yes |
| Climate shocks (drought, cold winter, heat wave) | yes |
| Volcanic winter | yes |
| Weather regimes, jet stream, wind | yes |
| Rain on windward slopes, rain shadow | yes (averaged over each place: a hex, or its low and high ground) |
| Snow pack and melt | yes |
| Weather known a week ahead | yes: cold, heat, deep snow and rain ahead, by each kind's foresight |
| Calendar and moon | yes (moonlight helps stalkers) |
| Volcanoes, eruptions, ash | yes (ash on places: plants, soil, grazers) |

## Water

| Feature | Hex |
|---|---|
| Soil water, evaporation, groundwater | yes (per band) |
| Rivers routed downstream | yes: hex to hex, carrying what the land sheds (unscaled since the water fix); lakes are a saved stock (`HexHydrology`) |
| Floods on the floodplain | yes |
| Rivers as barriers, fords | yes (on hex edges, by depth); no spring spate |
| Wetlands drowning woody plants | yes |
| Lakes | yes: water hexes; fresh lakes give drinking water |
| Drinking water, thirst, distance to water | yes (share of each place within a walk of water, each week) |
| Springs and oases | yes (from the 1 km terrain) |
| Dams, canals, irrigation, rerouting rivers | no |

## Plants and soil

| Feature | Hex |
|---|---|
| Herbs, shrubs, trees: growth, shading, die-back, seed spread | yes |
| Frost and flood damage | yes |
| Wildfire, spreading with the wind | yes |
| Green flush after fire | yes |
| Grazing removes fire fuel; browsing holds back saplings; lawns | yes |
| Forage quality (green vs dry) | yes |
| Separate food pools (roots, mast, fruit; straw is the cured grass) | yes |
| Roughage value by body size; selective feeders | gut type only (switched forage at [Species.fibreDigestion]); body size not |
| Boar rooting, seed carried by fruit-eaters | yes |
| Trampling and worn paths, paths easing travel | yes: worn paths ease the going, more so in broken country (`PathWear`) |
| Soil erosion, soil recovery | yes |
| Litter, soil life, organic matter | yes |
| Pollinators setting the fruit crop | yes |
| Cliffs keeping animals off food | yes |
| Sight and concealment from plants | partly: cover only |

## Small game, field populations, scavengers

| Feature | Hex |
|---|---|
| Small game (rodents, hares, hyrax) | yes |
| Insects, biting insects, reptiles and amphibians | yes |
| Wild birds, migrant birds | yes |
| Tortoises, foxes and wildcats | yes |
| Vultures | yes (colonies on broken ground, 80 km reach) |
| Carrion and scavenging | yes (rots by warmth) |
| Fire effects on small game | yes |

## Fish and sea life

| Feature | Hex |
|---|---|
| Fish in sea, lakes and rivers; shellfish; spawning runs | yes |
| Upwelling, whales, dolphins, porpoises, seals, sharks | yes (as densities per sea hex) |
| Sea-bird colonies | yes |
| Crocodiles, eagles | yes |

## Plant-eaters

| Feature | Hex |
|---|---|
| Resident kinds | yes, as densities per place (a hex, or its low and high ground): every plant-eater, the migratory kinds too, and jackal and lynx as resident hunters |
| Choosing where to go each week | yes (own rule, learnable) |
| Routes over passes, climbers and walkers | yes (graded borders) |
| Snow making travel harder | yes |
| Home ranges | partly: a preference for staying |
| Seasonal memory, inherited template | yes |
| Grazing shared by diet and need | yes |
| Births, growing up, splitting and merging | yes |
| Crowding disease | yes; outbreaks too (start where a kind is crowded, spread and burn out: `HexFauna.outbreak`) |
| Snow hiding grass | yes; deaths in deep snow no |
| Cold and heat (coat) | yes (coat grows in autumn, moulted in spring) |
| Fat reserves | yes (residents; autumn fattening; bears' autumn condition) |
| Hibernation (bears) | yes |
| Breeding season shifting to the food | yes |
| Thirst and water | yes |
| Wariness of people, boldness | partly: wariness after being hunted (sharper watch, fewer kills, fades over weeks); boldness waits for people |
| Vigilance, alarm, hiding in cover | yes: watch in open country, wary after a chase, small prey hide in cover |
| Dangerous prey fighting back | yes |
| Flood, ash, fire deaths | yes |

## Hunters

| Feature | Hex |
|---|---|
| Hunting along the way, meetings settled in order | yes (new on hexes) |
| Catch chance | yes: size, defence, refuge, hiding, moonlight, speed; posture as wariness |
| Territories, rivals, pack breeding | yes |
| Scent marks | yes |
| Bigger hunters killing smaller ones | yes |
| Stealing kills | yes |
| Extra deaths for packs without ground | yes |
| Small prey, scavenging | yes |
| Following people's camps | partly: bold animals of kinds that follow people are drawn to camps and tallied for taming |

## People

| Feature | Hex |
|---|---|
| Bands as groups, camp choice (game, plant food, water, a safe spot) | yes |
| Gathering fruit, nuts, roots and grass seed over the week's walk | yes |
| Hunting resident game; snares and nets; fishing and shellfish | yes |
| Larder (dried meat, stored plant food), caches at old camps | yes |
| Season memory, news of good ground and food gifts when bands meet | yes |
| Skills learned by practice and forgotten unused | yes (`Skill.kt`: about 34 variants in 24 kinds of work, among them tracking, stalking, butchery, tending, healing and lore) |
| Tools made from local materials, worn out, carried | yes (spear, bow, net, sickle, grinding stone, basket, clothes) |
| Tools hit on and shown to bands met | yes |
| Camp kept: births closer with stores, children sicken in a fouled camp, bands leave fouled camps | yes |
| Traditions with effects, copied from bands doing better, taken up and dropped | yes (ten) |
| Language as words per concept, understanding between bands | yes (naming game, drift) |
| Marrying out, lineage mix, inbreeding | yes |
| Elders and grandmothers | yes |
| Raids for food and feuds, grudges against lineages | yes |
| Desert kites | removed (later than the Natufian; Olli, 2026-10-15) |
| Crowding sickness | yes (people in the hex and the six round it) |
| Burning off, camp fires against lions and hyenas, defensible camps | yes |
| Knowing the country (walked, seen, told of by bands understood), forgetting far country | yes |
| Sharing on demand: hungry bands fed by better-stocked bands nearby | yes |
| Camp followers growing bold, pups taken, dogs kept (hunting, guarding) | yes |
| Firewood, worn routes, scouting, land claims, ornaments and renown, gatherings | yes (firewood fetched for the hearth and the night fire; routes and landmarks in `HexRoutes`; scouting and gatherings as band ways) |
| Middens, task groups | no (middens only as part of keeping camp clean) |
| Keeping herd animals, tending plants | partly: keeping young animals and sowing are band ways; `TENDING` is a skill |
| Learned bands | yes: people's mover and the bands' ways trained by PPO (see Learning) |

## Learning

| Feature | Hex |
|---|---|
| Learnable choice of where to go | yes: one mover per role (hunters, people; the herds' role is kept in the layout), trained by PPO (`HexPpo`, `PpoTrainer`) |
| Copying the hand-written behaviour, evolution | yes: imitation is PPO's warm start; evolution remains for the ways (`HexEvolveWays`) |
| Learned bands | yes: bands' ways by PPO (`WaysPpo`) or evolution |

## New on hexes, not in the 1 km model

- Low and high ground inside each hex with enough relief, with animals moving between them.
- Border crossings graded by way of travelling, with rivers on hex edges.
- Meetings timetabled within the week, which can cut a group's week short.
- A sense of direction for every kind (`HexBearings`): each month, the way to better country it
  knows of within 60 km, peaks it can see out to 200 km (standing for their massifs), and a pull
  toward cooler or warmer country when the month is wrong for its coat. Groups trek when far out of
  their country (packs leave their ground); resident kinds spill that way. The check's `uniformStart=true`
  starts every kind spread evenly.

## Still missing

The people features marked "no" above, and dams, canals and irrigation (the 1 km code is kept for
porting with people). The old work list and closed-ecology rules (hex-gaps.md) are in the project's
archive (archive/repo-docs-2026-10-18/design/ in the shared folder).
