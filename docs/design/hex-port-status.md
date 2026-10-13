# Hex model: what is ported from the 1 km model

Status on 13 Oct 2026. For each feature of the 1 km (grid) model: **yes** ported, **partly** ported
(what is missing), **no** not yet. The hex model runs from the hex viewer, the hex checks and the
hex learning tools; it is not yet wired into `Simulation`.

## Weather and climate

| Feature | Hex |
|---|---|
| Stochastic weather around the normals | yes |
| Five-year and decadal swings | yes |
| Climate shocks (drought, cold winter, heat wave) | yes |
| Volcanic winter | no (no eruptions on hexes) |
| Weather regimes, jet stream, wind | yes |
| Rain on windward slopes, rain shadow | yes (averaged per height band) |
| Snow pack and melt | yes |
| Weather known a week ahead | partly: temperature only, for learned herds; no rain-ahead or hazard sense |
| Calendar and moon | partly: months yes; moonlight unused |
| Volcanoes, eruptions, ash | no |

## Water

| Feature | Hex |
|---|---|
| Soil water, evaporation, groundwater | yes (per band) |
| Rivers routed downstream | partly: hex to hex, held to long-term flows |
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
| Green flush after fire | no |
| Grazing removes fire fuel; browsing holds back saplings; lawns | yes |
| Forage quality (green vs dry) | yes |
| Separate food pools (roots, mast, fruit, straw) | partly: read from standing plants |
| Roughage value by body size; selective feeders | no |
| Boar rooting, seed carried by fruit-eaters | partly: the plants respond, but the animals don't set them yet |
| Trampling and worn paths, paths easing travel | partly: in place but nothing tramples yet |
| Soil erosion, soil recovery | yes |
| Litter, soil life, organic matter | yes |
| Pollinators setting the fruit crop | yes |
| Cliffs keeping animals off food | no |
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
| Fish in sea, lakes and rivers; shellfish; spawning runs | no |
| Upwelling, whales, dolphins, porpoises, seals, sharks | no |
| Sea-bird colonies | no |
| Crocodiles, eagles | no |

## Plant-eaters

| Feature | Hex |
|---|---|
| Resident kinds | yes, as densities per height band |
| Migratory kinds as herds | yes |
| Choosing where to go each week | yes (own rule, learnable) |
| Routes over passes, climbers and walkers | yes (graded borders) |
| Snow making travel harder | no |
| Home ranges | partly: a preference for staying |
| Seasonal memory, inherited template | yes |
| Grazing shared by diet and need | yes |
| Births, growing up, splitting and merging | yes |
| Crowding disease | yes; random outbreaks no |
| Snow hiding grass | yes; deaths in deep snow no |
| Cold and heat (coat) | partly: fixed coat, no moult |
| Fat reserves | partly: herds only, no autumn fattening |
| Hibernation (bears) | no |
| Breeding season shifting to the food | no |
| Thirst and water | yes |
| Wariness of people, boldness | no |
| Vigilance, alarm, hiding in cover | partly: herds watch in open country and turn aside |
| Dangerous prey fighting back | no |
| Flood, ash, fire deaths | no |

## Hunters

| Feature | Hex |
|---|---|
| Hunting along the way, meetings settled in order | yes (new on hexes) |
| Catch chance | partly: size, defence, refuge in broken ground; no speed, posture or moonlight |
| Territories, rivals, pack breeding | yes |
| Scent marks | no (held ground is exclusive instead) |
| Bigger hunters killing smaller ones | no |
| Stealing kills | no |
| Extra deaths for packs without ground | no |
| Small prey, scavenging | yes |
| Following people's camps | no |

## People

None of it is on hexes yet: bands, camp choice, gathering, snares, fishing, hunting, kites, skills,
tools and making, stores and caches, firewood, settling, gatherings, language, mental maps,
scouting, land claims, raids and grudges, gifts and renown, traditions, sharing, marrying out,
lineage and inbreeding, elders, fear of lions and camp fires, defensible camps, burning off,
middens, worn routes, crowding sickness. (The plants can already take a fire people light.)

## Learning

| Feature | Hex |
|---|---|
| Learnable choice of where to go | yes (herds) |
| Copying the hand-written behaviour, evolution | yes (herds; hunters optional as hand-written) |
| Learned bands | no |

## New on hexes, not in the 1 km model

- Height bands inside each hex, with animals moving between them.
- Border crossings graded by way of travelling, with rivers on hex edges.
- Meetings timetabled within the week, which can cut a group's week short.

## Biggest gaps, in rough order

1. People.
2. A sense of direction to better country: climate and biome pull, far landmarks in sight.
3. Wariness, boldness, vigilance and hiding.
4. Stealing kills, bigger hunters killing smaller, scent marks, moonlight.
5. Fish and sea life; crocodiles and eagles.
6. Volcanoes and ash.
7. Animals trampling, rooting and carrying seed (the plants are ready for it).
8. Fire killing and driving animals; green flush after fire.
9. Breeding season shifting, hibernation, outbreaks.
10. Dams, canals and irrigation.

Done since the first list: drinking water, springs and thirst; soil life; the small life
(insects, rodents, reptiles, pollinators, hares, hyrax, birds, tortoises, foxes, vultures).
