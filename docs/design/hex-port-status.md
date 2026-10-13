# Hex model: what is ported from the 1 km model

Status on 13 Oct 2026, after the ground-up port. For each feature of the 1 km (grid) model: **yes** ported, **partly** ported
(what is missing), **no** not yet. The hex model runs from the hex viewer, the hex checks and the
hex learning tools; it is not yet wired into `Simulation`.

## Weather and climate

| Feature | Hex |
|---|---|
| Stochastic weather around the normals | yes |
| Five-year and decadal swings | yes |
| Climate shocks (drought, cold winter, heat wave) | yes |
| Volcanic winter | yes |
| Weather regimes, jet stream, wind | yes |
| Rain on windward slopes, rain shadow | yes (averaged per height band) |
| Snow pack and melt | yes |
| Weather known a week ahead | yes: cold, heat, deep snow and rain ahead, by each kind's foresight |
| Calendar and moon | yes (moonlight helps stalkers) |
| Volcanoes, eruptions, ash | yes (ash on places: plants, soil, grazers) |

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
| Green flush after fire | yes |
| Grazing removes fire fuel; browsing holds back saplings; lawns | yes |
| Forage quality (green vs dry) | yes |
| Separate food pools (roots, mast, fruit, straw) | yes |
| Roughage value by body size; selective feeders | yes (straw by gut size) |
| Boar rooting, seed carried by fruit-eaters | yes |
| Trampling and worn paths, paths easing travel | partly: trampling and paths yes; paths do not ease travel on hexes |
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
| Resident kinds | yes, as densities per height band |
| Migratory kinds as herds | yes |
| Choosing where to go each week | yes (own rule, learnable) |
| Routes over passes, climbers and walkers | yes (graded borders) |
| Snow making travel harder | yes |
| Home ranges | partly: a preference for staying |
| Seasonal memory, inherited template | yes |
| Grazing shared by diet and need | yes |
| Births, growing up, splitting and merging | yes |
| Crowding disease | yes; random outbreaks no |
| Snow hiding grass | yes; deaths in deep snow no |
| Cold and heat (coat) | yes (coat grows in autumn, moulted in spring) |
| Fat reserves | yes (herds; autumn fattening; bears' autumn condition) |
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
- A sense of direction for every kind (`HexBearings`): each month, the way to better country it
  knows of within 60 km, peaks it can see out to 200 km (standing for their massifs), and a pull
  toward cooler or warmer country when the month is wrong for its coat. Groups trek when far out of
  their country (packs leave their ground); resident kinds spill that way. The check's `--uniform`
  starts every kind spread evenly, and `-Dhex.noBearings` turns the sense off for comparison.

## Still missing

People (everything in the section above), dams, canals and irrigation (the 1 km code is kept for
porting with people), worn paths easing travel, random outbreaks of disease. The work list and the
closed-ecology rules are in `hex-gaps.md`.
