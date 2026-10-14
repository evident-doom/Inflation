# Skills: what rests on constants today, and what could be learned

Inventory, 2026-10-14. It lists every people's ability now set by a fixed constant and asks two questions of each: is it learned in reality, and do tools or materials change it? The skills a band teaches all its children are a large part of its culture. Which skills, and which variants of them, a band holds marks it out as much as its customs do.

## Already skills (level 0..1, learned by practice, fading, spread by partners)

- **Foraging** (`Forage`): gathering, small game, fishing, hunting, storing.
- **Making** (`Making`): knapping, woodwork, hide work, cordage, fire.
- **Plant knowledge**, for each kind of country.
- **Tools** (`Tool`): spear, bow, net, sickle, grinder, basket, clothes. A band knows a tool or not, and its kit wears.

## Fixed today, learned in reality

Each row is a candidate skill. It has variants by tool or material, and the constants it would replace.

| Domain | Skill | Variants (tools, materials) | Constants it would replace |
|---|---|---|---|
| **Hunting** | tracking and reading sign | dogs | `TRACKING`, `SEARCHING` |
| | stalking and ambush | cover, blinds, camouflage | `AVOID_SKILL`, `SURPRISE`, `CATCH` |
| | killing at range | thrusting spear, spear-thrower, bow, poison | `WEAPONLESS`, `BOW_GAIN` |
| | drive hunting | kites, nets, fire drives; needs organising | `KITE_*` |
| | butchery and marrow | flakes, hammerstones | `MEAT_SHARE`, `BONE_DAYS`, `HANDLING_DAYS` |
| **Small game and fish** | trapping | snares, deadfalls, nets | `SMALL_SENSE_KM`, `SMALL_CATCH`, `NET_GAIN` |
| | fishing | hand, spear, hook and line, weirs, traps, nets, boats | `FISH_HANDLING_DAYS_PER_KG` |
| | shellfish and shore | — | (in gathering) |
| **Plant food** | digging | digging stick (weighted), knowledge of tubers | `ROOT_DIGGING` |
| | seed processing | threshing, winnowing, grinding, baking | `SEED_HANDLING_DAYS_PER_KG`, `RAW_SEED` |
| | detoxifying | leaching acorns, roasting, soaking; turns inedible plants into food | `PALATABLE_FLOOR` |
| | cultivation | clearing, sowing, weeding, seed choice, burning | `SOW_DAYS_PER_KG`, `FIELD_YIELD`, `SICKLE_SELECT`, `BURN_OFF` |
| **Cooking** | cooking | roasting, stone boiling (needs containers), earth ovens, pots | `RAW_COST`, `RAW_SEED` |
| | brewing and fermenting | containers, grain | `BEER_KG` |
| **Keeping food** | drying and smoking | racks, fire | `DRY_DAYS_PER_KG` |
| | storage | pits, caches, baskets, skins, stone bins, plaster, pots | `PIT_KEEP`, `MICE`, `CACHE_SPOIL`, `CACHE_FIND`, `STORE_FILL` |
| **Water** | finding and carrying water | skins, gourds, eggshells, wells | `HUMAN_THIRST` |
| **Shelter and warmth** | clothing | wrapped hide, sewn and fitted (needles), footwear; tanning | `CLOTHES_C`, `PROTECT_CLOTHES` |
| | building | windbreak, debris hut, pit house, stone-footed hut, mudbrick | `SHELTER_*`, `HOUSE_*` |
| | fortifying | brush, palisade, ditch, wall | `PALISADE_*` |
| **Moving** | carrying | bags, baskets, carrying frames, dogs, boats | `CARRY_KG`, `GOODS_PER_PERSON` |
| | walking in hard country | footwear, snowshoes | `WALK_COST`, `SNOW_EFFORT_MM` |
| | boats | rafts, dugouts, skin boats | (none: rivers and the sea are not routes yet) |
| **Knowing the land** | navigation and landscape lore | — | `SIGHT_*`, `SCOUT_*`, `KNOWN_AT_START`, `UNKNOWN_COUNTRY` |
| | weather and sky lore | calendars by moon and stars | `RAIN_AHEAD_MM`, appointment keeping |
| **Health** | medicine | herbs, wound care, bone setting | `PEOPLE_SICK`, `MARSH_FEVER`, the `MEDICINE` custom |
| | midwifery and child care | weaning foods (gruel), carrying | `youngMortality`, `SETTLED_FERTILITY` |
| | camp hygiene | middens, latrines, moving camp | `CAMP_FOULING` |
| **Animals** | taming and herding | pens, fences, dogs | `CAPTURE`, `TAMING_RATE`, `HERD_RUN_OFF`, `HERD_MOVE_LOSS`, `HERD_PER_PERSON` |
| | dog keeping | — | `DOG_*` |
| **Crafts** | bead and ornament making | drills, abrasives | `ORNAMENT_DAYS`, `SHELL_EACH` |
| | ground stone | grinders, axes for clearing forest, stone vessels | `GRINDER` recipe |
| | weaving and basketry | looms, fibre | (in cordage) |
| | pottery (later) | clay, kilns | (none) |
| **Fighting** | war | shields, ambush, formations, scouts | `RAID_DEATHS`, `FIGHT_RATIO`, `SURPRISE_RAID`, `FLEE` |
| **Learning itself** | teaching and memory | oral tradition, songs, marks | `PRACTICE`, `FORGET`, `TEACH`, `INVENT` |

## Kept constant (not skills)

Body and physics stay fixed: daily need, walking cost per km, how long a person lasts without water, gestation, ageing, and how fast meat rots in heat. Skills change how people meet these limits, not the limits themselves.

## Proposal: one generic skill system

- **Skills:** each is a domain plus a variant, for example cooking by stone boiling, or fire by hand drill. Each has a level 0..1, a floor, a practice rate and a fade rate.
- **Tools and materials** multiply what a skill does. A variant needs its tools: stone boiling needs containers, and bows need sinew and resin.
- **What each constant above becomes:** a base value (the body or physics) times what the skill does, so a practised band with good tools does better.
- **How skills change:** they grow by doing, and fade without it (more slowly where songs and stories keep them). They are taught to the young, so everyone in the band holds the basics. They spread to partners and to bands that meet. New variants are hit on now and then by bands that have a use for them, as tools are now.
- **Choices:** what a band spends its spare time practising and making is one of its ways, which the learned networks can take over.
- **Culture:** a band's set of skills and variants is part of its customs. It's reported and compared like words and traditions, so neighbours share packages and distant bands differ.

**Order of work proposed:**
1. The generic system, replacing the current `craft` and `making`.
2. Cooking and detoxifying, which unlock plant food.
3. Food keeping and containers.
4. Clothing and building variants.
5. Medicine and child care.
6. Hunting subskills.
7. Moving and boats.
8. The rest.

## Status (2026-10-14)

Built:
- **The generic system** (`Skill.kt`): kinds of work (`Domain`), each done by one or more variants (`Skill`). Each variant has a floor, a start level, whether bands know it from the start, and a gain. Bands invent new variants where they practise the work, show them to bands they meet, and partners bring theirs. The check prints "ways of working": the share of bands that know each variant and their mean level.
- **Cooking:** roasting, stone boiling, earth ovens.
- **Detoxifying:** leaching.
- **Keeping food:** sun drying (slowed by rain), smoking, sealing pits.
- **Clothing:** sewing; clothes are warmer with the skill.
- **Building:** stone footings, pit houses, mudbrick. A band builds the way its materials go furthest.
- **Healing:** herbs.
- **Child care:** midwifery.

Skills that existed before keep their behaviour at a start band's level. New variants change nothing until a band knows them.

Next: hunting subskills (tracking, stalking, drives, butchery), moving (carrying, snowshoes, boats), animal handling, the remaining crafts, fighting.
