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
| Resident kinds | yes, as densities per place (a hex, or its low and high ground): every plant-eater, the migratory kinds too, and jackal, lynx and striped hyena as resident hunters |
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
| Fouling is the ground's, not the band's: it builds with the people camped at a hex and clears while nobody camps there, so newcomers find it; a midden kept by bands that keep their camps clean spares every band at the hex, whatever its customs | yes |
| Wells: a settled skill hit on by bands at home that have had to dig for their water; a shaft sunk below the water table (up to 13 m) at days by depth, halved with picks, draws water at a fifth of a soak's time for anyone at the hex while the table stands above it; wells silt up and are mended, deepened when the water sinks, or fill in | yes |
| Traditions with effects, taken up and dropped: know-how copied from bands doing better, marks (burial, leaving the dead, marrying out, sparing young game, feasts) from the many bands met | yes (ten) |
| A totem per lineage: a resident kind its hunters leave alone unless hungry; passed on as a mark, a custom bands count each other apart by, not seen kept by a band known to have eaten of it | yes |
| Burial ways for bands that bury: at camp, or carried to where most of their dead lie if within a day's walk; digging and carrying cost customs time; passed on as a mark | yes |
| Rites oblige: in the rite's month each adult owes customs days and the walk to the site and back; held once given, else cut short; sanctity per place from rites and graves (fading over a generation), holier places draw new rites | yes |
| Rites adjust to the season: one the month could not give its days is held in a lesser form with what was given; the belief is strained only as far as the days not given went to other work than the band's needs; a rite cut short by need moves to a month beside it the band eats better in. Rites are drawn to the turns of the year (equinoxes, solstices) as they are born and moved | yes |
| Rite gatherings: bands that hold a rite at the same site in the same month (in full or in a lesser form) meet there as bands that come across each other do (news, gifts, words, customs, rites; a raid if their regard comes to it); no truce is imposed | yes |
| Shared rites in regard: two bands that both keep rites and share no rite site are one custom further apart (as burial ways count only where both bury); a site in common, in any month, counts as one faith there | yes |
| Peace at shared rites: at a rite gathering, a band is less ready to raid another that keeps the same rite there, as far as it holds to its rites (1/(1 + its hold); estimate); one that raids there anyway is held against by the other bands gathered that keep the rite (grudge against its lineage, ties lost), as far as each holds to its rites. A pattern bands keep by choice, not a ban | yes |
| Sickness and rite gatherings: a band showing sickness keeps away from a rite's gathering as far as its will to shun the sick goes (the same draw as at meetings), and holds the rite where it is | yes |
| Livelihood and home ground in regard: bands that live otherwise (farming while they keep seed corn of their own crop, herding while they keep a herd, foraging otherwise) are one custom further apart; so are two bands with homes more than a day's walk apart (a band without a home is not counted) | yes |
| Belief strain from rites held back from is one of the quarrels that split a band: the more strained (against what the belief bears; older beliefs bear more), the smaller the band holds together, and the part that leaves at a split goes without the belief as likely as the strain is near what it bears; strain alone never splits a band | yes |
| A band at home splits at a village's size (150, more with feasts); the halves of a split hold each other at a full tie; bands at home in one hex that find each other alike enough both ways (words, kin, tie, customs) become one band while there is room | yes |
| Unused houses are nobody's: a band takes up free houses at its hex only as far as it needs them for its grown people, keeping those it already uses | yes |
| Rites born of omens: a week's death toll far above the usual, plenty after long want, or a strange sign drawn at random (about one a year, a third salient) where the band is, borne out by the same month going well there again or by another band that marked the same place; no yearly take-up and no letting rites go; rites copied between bands met as far as each trusts the other, not from the better-off | yes |
| Language as words per concept, understanding between bands | yes (naming game, drift) |
| Marrying out, lineage mix, inbreeding; partners are exchanged the likelier the better the bands understand each other and the fewer customs they hold apart (the less willing of the two; estimate) | yes |
| Elders and grandmothers | yes |
| Raids for food and feuds, grudges against lineages; one regard of the other band (understanding, kin, tie, grudge, strangers, customs apart) sets raids at meetings and on homes, cache robbing, gifts at meetings and on visits in hunger (a band gives more to a band it is close to, and the hungry go where they can hope for most), the trust in what it is told, the taking up of its meeting place, whom it counts friend or foe, and whose known homes it keeps off | yes |
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
