# Skills: what is learned, and the constants it touches

This lists people's abilities that are learned in reality and asks two questions of each: which constants of the model it touches, and do tools or materials change it? The skills a band teaches all its children are a large part of its culture. Which skills, and which variants of them, a band holds marks it out as much as its customs do.

Skills are kinds of work (`Domain`), each done by one or more variants (`Skill`), in `Skill.kt`. Tools (`Tool`: spear, bow, net, sickle, grinder, basket, clothes) are known or not, and a band's kit wears.

## Learned in reality

Each row is a skill. It has variants by tool or material, and constants of the model it touches.

| Domain | Skill | Variants (tools, materials) | Constants |
|---|---|---|---|
| **Hunting** | tracking and reading sign | dogs | `SEARCHING`, the `TRACK` skill |
| | stalking and ambush | cover, blinds, camouflage | `AVOID_SKILL`, `SURPRISE`, `CATCH` |
| | killing at range | thrusting spear, spear-thrower, bow, poison | `WEAPONLESS`, `BOW_GAIN` |
| | drive hunting | nets, fire drives; needs organising | (none) |
| | butchery and marrow | flakes, hammerstones | `MEAT_SHARE`, `BONE_DAYS`, `HANDLING_DAYS` |
| **Small game and fish** | trapping | snares, deadfalls, nets | `SMALL_SENSE_KM`, `SMALL_CATCH`, `NET_GAIN` |
| | fishing | hand, spear, hook and line, weirs, traps, nets, boats | `FISH_HANDLING_DAYS_PER_KG` |
| | shellfish and shore | — | (in gathering) |
| **Plant food** | digging | digging stick (weighted), knowledge of tubers | `ROOT_DIGGING` |
| | seed processing | threshing, winnowing, grinding, baking | `SEED_HANDLING_DAYS_PER_KG`, `RAW_SEED` |
| | detoxifying | leaching acorns, roasting, soaking; turns inedible plants into food | the `LEACHING` skill |
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
| | boats | rafts, dugouts, skin boats | (none) |
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
| | pottery | clay, kilns | (none) |
| **Fighting** | war | shields, ambush, formations, scouts | `RAID_DEATHS`, `FIGHT_RATIO`, `SURPRISE_RAID`, `FLEE` |
| **Learning itself** | teaching and memory | oral tradition, songs, marks | `PRACTICE`, `FORGET`, `TEACH`, `INVENT` |

## Kept constant (not skills)

Body and physics stay fixed: daily need, walking cost per km, how long a person lasts without water, gestation, ageing, and how fast meat rots in heat. Skills change how people meet these limits, not the limits themselves.

## One generic skill system

- **Skills:** each is a domain plus a variant, for example cooking by stone boiling, or fire by hand drill. Each has a level 0..1, a floor, a practice rate and a fade rate.
- **Tools and materials** multiply what a skill does. A variant needs its tools: stone boiling needs containers, and bows need sinew and resin.
- **What each constant above becomes:** a base value (the body or physics) times what the skill does, so a practised band with good tools does better.
- **How skills change:** they grow by doing, and fade without it (more slowly where songs and stories keep them). They are taught to the young, so everyone in the band holds the basics. They spread to partners and to bands that meet. New variants are hit on now and then by bands that have a use for them, as tools are.
- **Choices:** what a band spends its spare time practising and making is one of its ways, which the learned networks can take over.
- **Culture:** a band's set of skills and variants is part of its customs. It's reported and compared like words and traditions, so neighbours share packages and distant bands differ.

In `Skill.kt`, each variant has a floor, a start level, whether bands know it from the start, and a gain. Bands invent new variants where they practise the work, show them to bands they meet, and partners bring theirs. The check prints "ways of working": the share of bands that know each variant and their mean level. A start band's level gives the base behaviour, and a variant changes nothing until a band knows it. Among the variants:
- **Cooking:** roasting, stone boiling, earth ovens.
- **Detoxifying:** leaching.
- **Keeping food:** sun drying (slowed by rain), smoking, sealing pits.
- **Clothing:** sewing; clothes are warmer with the skill.
- **Building:** stone footings, pit houses, mudbrick. A band builds the way its materials go furthest.
- **Healing:** herbs.
- **Child care:** midwifery.
- **Hunting and animals:** tracking, stalking, butchery and animal tending (`TRACK`, `STALK`, `BUTCHER`, `TENDING`).
- **Toolmaking:** knapping as flakes, blades and microliths. Blades and microliths are faster and take 40% of the flint, and bands use whichever way they do best. Also ground stone (pecking, for grinders), bone tools (needles and awls, practised with clothes) and hafting (glued and bound tools wear out more slowly).
- **Meta skills:** showing (apprenticeship: all practice goes faster or slower), lore (songs and stories: forgetting slower or faster) and tinkering (new tools and ways hit on more or less often). Each is kept up by use: teaching the young, telling, and making things.

Bands try the other ways of a craft now and then, so a better new way can overtake the old one.

## Keeping skills from spreading uniformly

If learning cost nothing, a band could hold every skill at once, a single meeting passed a way on, and forgetting were weak, every skill would drift to every band. The record shows the opposite: regional toolkits, crafts lost by small or isolated groups (Tasmania, after it was cut off), and specialists.

The mechanisms, most important first (in `HexPeople.kt` and `HexCulture.kt`):

1. **Skills live in people.** Each skill has a number of holders in the band, not just a level. A band can keep only so many people practised: each person has time for a few crafts. The band's level in a skill depends on its best few holders. When a band splits, its holders are divided between the two bands, so a splinter group can lose a craft outright. Small, isolated bands lose complex skills, and large, connected ones keep them (Henrich's model of cultural loss). (`Band.holders`, divided on a split, with `SLOTS` ways a person keeps up.)
2. **Learning takes time.** Practice and teaching come out of the spare days the band also spends on making, building and feasts. What a band practises is one of its choices, for the learned ways to take over. Specialisation follows: a band invests in what its country rewards. (Training holders takes person-days from the band's spare time: `APPRENTICE_DAYS`.)
3. **Passing a skill on is lossy and needs long contact.** Learners reach on average a little below their teacher. One meeting can show that a way exists, but the skill itself comes only with people who stay: partners who marry in, bands that join, visits at gatherings. (`TRANSMIT_LOSS`.)
4. **Forgetting by disuse.** A skill unused for a few years fades fast. Lore slows this only for what is sung and told (plants, places, the sky), not for hand skills. (`FORGET_HANDS`, `FORGET_KNOWLEDGE`.)
5. **Prerequisites.** Some ways need others first: microliths need blades, stone boiling needs containers, sealing needs settled life, and later pottery needs good fire control. (`Skill.needs`, `NEEDS_LEVEL`.)
6. **Conformity.** Bands take up a stranger's way less readily the more their customs and words differ, and more readily from bands that are doing well (prestige). (`CONFORMITY`, `PRESTIGE`.)

Together, 1, 2 and 3 give regional toolkits, loss in isolation, and specialists.

## What the record says about how skills grow, cap and fade

These are from memory of the literature, with authors named for following up. The figures are approximate.

**Learning curves and peaks:**
- **Hunting** (Koster and colleagues 2020, 40 societies; Gurven, Kaplan and Gutierrez 2006 on the Tsimane; Walker and colleagues 2002 on the Aché):
  - Returns rise slowly through the teens.
  - Hunters reach about half their peak around 20.
  - Returns peak in the 30s and fall slowly after 45.
  - Skill (knowledge, tracking) keeps rising after strength has peaked.
- **Gathering** (Bird and Bliege Bird; Blurton Jones on the Hadza):
  - Easy foods (shellfish, fruit, baobab) reach adult rates by the early teens.
  - Hard ones (deep tubers) wait for adult strength.
  - Skill matters more where food must be processed.
- **Knapping** (Stout; Pargeter, Khreisheh and Stout 2019):
  - Oldowan flakes take hours to learn.
  - Novices given about 90 hours of coached practice still fall short of experts at handaxes.
  - Levallois cores, blades and pressure flaking take years.
- **Expertise in general:** practice follows a power law, with fast early gains and a long plateau. Mastery of complex crafts takes about ten years (Ericsson).

**Forgetting** (Arthur and colleagues 1998, a meta-analysis of skill decay):
- Motor skills practised in a continuous flow are kept for years with little loss: knapping, throwing, sewing, swimming.
- Procedural knowledge with many discrete steps, and facts, fade much faster: the steps of a ritual, which plant is safe, a recipe.
- So disuse should erode knowledge quickly but hand skills slowly.

**Group size and toolkits:**
- Henrich 2004 (Tasmania): small, isolated groups lose complex skills.
- Kline and Boyd 2010: Oceanic islands with more people and more contact had more, and more complex, tools.
- Collard, Buchanan and colleagues found toolkit diversity follows environmental risk (latitude, cold) more than population.
- Torrence and Oswalt: forager toolkits range from 10–20 items in warm country to over 100 in the Arctic.

**Specialists:**
- Mobile foragers have few specialists, mainly healers.
- Sedentary, storing foragers (the Northwest Coast) and early villages have craft specialists.
- About 1 in 10–20 adults is a recognised healer or ritual specialist.

**How to set the model's ranges from this:**
- Level 1 is a master in the 30s. A band's best holders sit at about 0.8–0.9 in skills it uses daily.
- Hunting returns at about 0.5 of peak for a young adult.
- A complex craft takes years of practice to reach 0.8: blades, microliths and, later, pottery.
- Forgetting is fast for knowledge (plants, recipes, ritual) and slow for hand skills.
- Toolkit breadth follows risk and contact: wide in cold, variable country and among well-connected bands.
