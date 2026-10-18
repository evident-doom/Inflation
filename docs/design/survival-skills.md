# Survival skills: a ladder from the naked floor, with checkpoints

The skills are in `Skill.kt` and `HexPeople.kt`.

## Idea

Survival rests on specific skills. Each skill:
- is modelled on its own;
- starts near zero for an unprepared person;
- grows with practice and fades without it (as the crafts do: `PRACTICE`, `FORGET`);
- is multiplied by the tools it uses.

The naked-person floor (the skills floor test in [neolithic-path.md](neolithic-path.md)) is the bottom rung. Survival shows, experimental archaeology and ethnography give checkpoints for how long each rung takes, and with what tools.

## Skills to model, with checkpoints

Figures are from memory of survival shows (Alone, Naked and Afraid), experimental archaeology and ethnography. They are approximate and to be checked.

| Skill | Without it | Checkpoints (novice → practised) | Tools that lift it |
|---|---|---|---|
| **Fire making** | No warmth at night, no cooking, unsafe water. Cold and wet kills in hours to a night. | Hand drill: novices often fail for days; the practised take minutes. Flint and pyrite strike: minutes. Keeping a fire going is easier than starting one. | Hand drill or bow drill (wood, cordage); flint and pyrite; tinder kept dry. |
| **Shelter** | Exposure, poor sleep, more food burnt. | Debris hut: 2–4 hours for a novice who knows how, about an hour practised. A brush windbreak: under an hour. | Cordage, hides, a stone or wood edge. |
| **Water** | Thirst kills in about 3 days; bad water brings sickness. | Finding water depends on knowing the land. Boiling needs fire and a vessel (hide, stones heated in a pit). | Containers, fire. |
| **Plant knowledge** | Wrong or poisonous plants; little food from plants. | People who don't know the local plants eat little and risk poisoning. Foragers know hundreds of species, learned over childhood. | Digging sticks, baskets, grinders. |
| **Trapping and snaring** | Little small game. | Snares give one catch per tens of trap-nights for a novice, and a few times better for the practised. Catches come after days of setting. | Cordage, nets. |
| **Hunting and tracking** | Bare-handed: eggs, nestlings, slow small animals. | Persistence hunting is possible but rare. With spears or bows, success on big game takes years to learn (forager hunters peak at around 30–40). | Spear, bow, dogs. |
| **Fishing and shore gathering** | Shellfish by hand at low tide. | Shellfish need almost no skill. Line, trap and net fishing take days to learn and seasons to master. | Hooks, lines, nets, weirs. |
| **Toolmaking** (exists) | Bare hands, unworked stones. | A usable flake: minutes. A good handaxe or blade core: hours, after months of practice. | Hammerstones, antler. |
| **Clothing** (exists as a tool) | Cold bites below about 20 °C at rest. | A rough hide wrap: hours. Sewn, fitted clothes: days, with needles. | Needles, sinew, scrapers. |

**Survival-show outcomes to match:**
- *Naked and Afraid*: two people, no clothes, one tool each, 21 days. They lose about 10–15% of body weight, and many quit. The warm tropics are survivable; cold nights are the danger.
- *Alone*: one person, ten chosen items, cold northern forest. The best last two to three months before hunger ends it. Fire, shelter and fishing or snaring decide who lasts.

## How it fits

- Each band holds a level 0..1 in each skill: fire, shelter, water, plants, trapping, tracking and fishing beside the crafts (knapping, woodwork, hide work, cordage).
- **What each skill does:**
  - Fire cuts the cold need and lets food be cooked (more energy from it) and water made safe.
  - Shelter cuts the cold need at camp.
  - Plant knowledge sets the edible share a band can use, and the risk of poisoning.
  - Tracking, trapping and fishing scale those kinds of food.
- **How skills change:**
  - They grow with use and fade without it.
  - They are taught to the young within a band.
  - They spread between bands that meet, as tools do.
  - A band that knows a place's plants loses that advantage when it moves to unfamiliar land.
- **Exposure:** cold kills directly when a band has no fire, clothes or shelter, instead of only costing food.
- **Tests:**
  - The naked floor matches the record (exposure, thirst, starvation).
  - Survival-show conditions match the shows.
  - Real foragers at full skill match the ethnography.

## How the skills are modelled

- **Fire** is a making skill. Practised bands carry embers and relight at once. Novices try daily: 10% a day untrained, learning with each fire. Fire adds warmth, and without it meals are raw and take 30% more food.
- **Shelter** building is faster with woodwork skill.
- **Plant knowledge** is held for each kind of country, starting at 0.9 for the country round a band's first camp and 0.5 elsewhere. It is learned by gathering there (5% of the way a week) and from partners. Strangers reach less (`PLANT_FLOOR` 0.1) and are slower at gathering.
- **Handling time** for gathering, small game and fishing scales with skill, measured against a start band's level.
- **Floors** for each skill: gathering 0.15, small game 0.1, fishing 0.1, big game 0.05, storing 0.3.
- **Exposure** deaths: 5% a week for each °C below 10 °C, after protection. Full clothes protect 30 °C, fire 12, a shelter 5 and a house 10.
- **Water:** people on ground they don't know don't find its springs (they get the full thirst of an animal).
