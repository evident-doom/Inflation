# Settled residents and roaming packs: wolves and lions

Status: design agreed in outline (Olli 2026-11-02, answers under Decisions), not built.

## Why

Wolves and lions are about a third of a step's processor time. Measured on m5-1, one year, `profile=fine` (runs/pack-cost), with each phase's unsplit work spread by direct cost:

| | thread-ms a step | groups | hexes held |
|---|---|---|---|
| wolf | 760–1,090 | 4,303 | 3,977 |
| lion | 530–760 | 4,584 | 4,540 |
| whole step | ~3,900 | | |

Almost all of it is choosing (wolf 590, lion 340) and foraging (150, 170). But they stay put 84–88% of weeks and move 11 km (wolf) and 5 km (lion) a year. A resident hunter kind costs about the same at any numbers, because its field is walked over every land hex: jackal 83, hyena 75, lynx 62, brown bear 100 thread-ms a step.

The learned gate (#555, #561) doesn't close the gap. After 13 rounds of training it still weighs 92% of hunter weeks.

The biology splits the same way. Most wolves live in territorial packs and most lions in prides that keep to their ground. A minority roam: dispersing young wolves and pairs (often put at 10–20% of a population), and nomadic males and coalitions among lions. The roamers do what needs a group: they colonise empty ground, make long trips, take over territories and turn up at camps.

## The idea

Wolves and lions live both ways at once.

- **Settled:** a resident field, as jackals, lynx and hyenas have. Adults and young per km² of each place, fed, bred, killed and spread by the residents' rules.
- **Roaming:** packs as today, with the learned mover, the gate, hunting parties and the will to settle. Only those without ground are roamers.

Nothing fixes the share between them. It comes out of three flows:

1. **Leaving.** Young grown up in a crowded field leave as roamers.
2. **Settling.** Roamers that find vacant ground join the field there.
3. **Dying.** Roamers die as homeless packs do today.

The target is a roamer share of roughly 10–20% of heads, reached by tuning the leaving rate.

Leopards and cheetahs stay packs. As packs they cost less than a resident field would (81 and 20 thread-ms a step, against ~75).

## Mechanisms

### 1. A kind both resident and in groups

- New catalogue set `Catalogue.roamingResidents = setOf(WOLF, LION)`. It is catalogue data like `residentHunters`, not a config switch.
- `Catalogue.resident(s)` becomes true for them.
- `HexGroups.kinds` keeps them, and `Catalogue.learned` keeps them.
- `HexFauna.kinds` appends them **after** the migratory plant-eaters, so the tables of saved worlds keep their order and old saves load.
- A world saved before the change loads with empty fields for these kinds. Mechanism 5 fills them at load.

### 2. Leaving: field to roamers

In `breedAndDie`, the young of these kinds who grow up this week (`up`) may leave instead of joining the place's grown. The share that leaves:

```
crowd  = heads / usual[k]                              (the territories' numbers, as strife uses)
leaves = LEAVE_BASE + LEAVE_CROWD * clamp(crowd - 1, 0, 1)
```

- **Groups.** Leavers are gathered per hex and leave as whole groups: lone dispersers for wolves, and pairs or small coalitions of `s.foragingParty` for lions. The fractions left over carry to next week in a per-kind, per-hex remainder table, so nothing is lost or invented.
- **Starting state.** They start at the hex's place, with no ground, the field's fed and fat, and a fresh temper as split-offs get. Their draws come from the hex's own stream, so runs repeat.
- **Spill.** For these kinds, `spillHunters` keeps only its need-driven part (hunger, thirst, cold, snow). Moving to better country is what the roamers do, so the "better next door" and drift terms go. Settled packs don't wander.

These are the packs' own founding rule ("young hunters grown up leave a pack already as big as its kind's usual one") put on the field.

### 3. Settling: roamers to field

In `settle()`, a roamer of these kinds that would claim ground joins the field there instead, if the hex is vacant for it. Vacant means both of these hold:

- the field's heads there are under `usual[k]` (room for a territory), and
- no other roamer of its kind holds fresh marks there.

The join itself:

- Its adults and young are added over the hex's places by area.
- Its fat and fed are averaged into the field's by heads.
- The group is removed as a merged one. For the census it counts as "settled ground".
- The will to settle (`AnimalWay.SETTLE`) still decides whether it takes the chance, so the choice stays where it is learned today.

### 4. Meeting: roamers and the settled

The settled already meet everyone through `ResidentParties`: one stand-in band per kind and hex, settled by the packs' rules (Olli 2026-10-31: packs and residents by the same rules). For these kinds that covers:

- **Rivals.** A roamer meeting a settled stand-in of its own kind meets a rival on its home ground (`rivalsMeet`, with home advantage). It gives way or fights, and the losses come off the field or the roamer.
- **Other kinds and people.** Lions killing other kinds' young, wolves and lions against bands, and people hunting them all go through the stand-ins unchanged.
- **Takeovers.** A roamer that beats a stand-in on its home ground takes over: it joins the field there at once, and the stand-in's young it kills (`INFANTICIDE`) come off the field. This is the lion coalition's takeover of a pride and the wolf pair's of a territory.

### 5. Worlds

- **New worlds** seed the field from the kind's niche as other residents are seeded, and start a few roamers. The flows set the share from there.
- **Saved worlds: a one-shot migration**, a separate tool (`tools/MigrateRoamers.kt`) run once per saved world. It loads the old save, folds every wolf and lion pack that holds ground into the field of its home hex by mechanism 3's join, keeps the packs without ground as roamers, and writes a new save beside the old one. Nothing in the model's own load path knows about it, so the tool can be deleted once m5-1..3 and the training worlds are migrated.
  - Why migrate rather than re-settle: the A/B's main arm runs on the old world and the split arm on the migrated copy of the same world, with the same geography, plants and prey in the same state. That pairs the comparison. Re-settled worlds would differ from main's in everything and only show differences beyond that noise.
  - The artifact is one week's fold, and it shows: every settled pack lands in its home hex at once, so the field starts clumped where packs held ground. The first year of the A/B is left out of its judging, and the folding is checked by comparing heads before and after (they must match exactly).
  - If the fold leaves a visible mark after that year, re-settle instead (Olli: either is fine, no artifacts wanted).

### 6. Following people: the same for fields and packs

Today only groups of kinds that follow people (wolves, jackals) live by camps, grow bolder and give up pups (`keepAnimals`, through `followersByHex`). Jackals lost this when they became residents. The settled take part through the same stand-ins that meet for them:

- Each follower kind's field keeps a boldness per hex (a table beside its numbers). It drifts bolder where a band camps, as a pack's `bold` does, and fades back elsewhere.
- In `keepAnimals`, each band of a follower kind's field in the camp's hex (as many as its stand-in stands for) counts as a follower pack, with the hex's boldness and a pack's odds of giving up a pup, taken off the field's young there.
- Dogs that go wild from a resident kind join its field at the camp, bold of people, as a pack's would found a pack.
- Roaming packs follow as today.

This puts jackals back among the kinds a band can take pups from, and wolves keep it whether settled or roaming.

### What holds numbers down

- **Strife:** past `STRIFE_CROWD` times the usual numbers, settled hunters kill each other and each other's young. It already holds jackals, lynx and hyenas. As residents, leopards rose fivefold before strife existed.
- **Leaving:** it moves the surplus young out of crowded ground.
- **Roamers' deaths:** roamers die homeless, hungry or in fights, as homeless packs do now.

## What changes for the learning

- The hunters' role learns from roamers' choices, plus all leopards and cheetahs. Those are the choices that matter: where a disperser goes and when it settles. Settled weeks, which are mostly "stay", leave the data.
- The data shrinks. Wolf and lion recorded choices fall to perhaps a fifth.
- **Net compatibility.**
  - Net inputs are unchanged, so the kept nets load and play.
  - The critic's per-kind returns change their meaning for wolves and lions: a roamer's life is shorter and harder.
  - So training restarts the hunter critic and raises the hunters' net major. The first net under the new major becomes the baseline (model-compatibility, point 1).

## What's lost

- Settled packs lose their own identity: their temper, place memory and lineage. The field has none of those.
- Fights between neighbouring settled packs become strife deaths, not meetings.
- Boldness becomes a field per hex, not a pack's own. Nothing else of following people is lost (mechanism 6).

## Cost

| | Now (thread-ms a step) | Split: field + roamers at 15% (thread-ms a step) |
|---|---|---|
| wolf | 760–1,090 | ~80 + 115–165 |
| lion | 530–760 | ~80 + 80–115 |

The split saves about 1,050–1,500 thread-ms a step, roughly a third of all processor time. In wall time that is perhaps 80–110 ms of today's ~400 ms step. Lower roamer shares save more.

## Checks before it counts as working

The test is an A/B on m5-1..3, 10 years, 2 weathers each, against main. The split passes when:

- **Numbers:** wolf and lion heads land within ±25% of main's, with no trend over the last 5 years.
- **Roamer share:** 5–25% of heads.
- **Prey:** each prey kind's numbers and deaths by wolves and lions within ±15% of main's.
- **People:** people's kills of wolves and lions, and wolf and lion kills at bands' meetings, in the same order as now.
- **Limits:** no new breaks of the nominal limits, state or series.
- **Speed:** step time down at least 20%, measured one run at a time.

If those pass, a 2 h daytime training (Olli's call) retrains the hunters on the new major.

## Steps

1. Following people through the stand-ins (mechanism 6), for jackals now. It stands alone and is small. Done in the PR that brings this doc.
2. Plumbing: kinds both resident and in groups, census and tallies counting both ways. Neutral, because nobody leaves or settles yet: fields start empty and stay so, packs are as before. Done: their settled fields come after every other resident kind (old saves load with them empty), new worlds seed none and no newcomers come for them, and bands don't take them as totems yet (`HexFauna.totemKinds`; open for step 3: may a band keep the wolf or the lion as its totem once they live settled?).
3. Leaving, settling and takeovers (mechanisms 2–4), new worlds' seeding, and the one-shot migration tool. Tune `LEAVE_BASE` and `LEAVE_CROWD` against the roamer share, then migrate the test worlds.
4. A/B, then training.

Each step is one PR.

## Decisions (Olli 2026-11-02)

1. **Takeovers:** yes. A roamer that beats a settled stand-in on its ground takes it (mechanism 4).
2. **Roamer share:** from the flows first, not held to a set share.
3. **Following people:** both settled and roaming. Where possible, residents and packs work identically (mechanism 6).
4. **Old worlds:** a one-shot migration is fine if it is clean and can be removed later; otherwise settle anew. No artifacts wanted (mechanism 5).
5. **Other residents with roamers:** considered below.

## Other residents with roamers

The mechanism is general: any carnivore resident can be put in `roamingResidents`. What it costs is its roamer groups at about 0.1–0.25 thread-ms a group a step. What it adds is long-distance dispersal: across gaps of poor ground and water, and colonising country the field's spill reaches only slowly, a neighbouring hex every few weeks and only towards better ground. Hexes are 10 km across.

| Kind | Heads | Dispersal (from memory, unchecked) | Roamers at 5%, groups | Cost, thread-ms a step | Worth it? |
|---|---|---|---|---|---|
| brown bear | 28,500 | young males tens to 100+ km | ~1,400 lone | 140–350 | maybe: recolonising, meeting camps |
| lynx | 6,000 | young 30–130 km | ~300 lone | 30–75 | likely: cheap, disperses far |
| striped hyena | 12,700 | ranges widely, alone | ~630 lone | 60–160 | doubtful |
| jackal | 75,000 | mostly 5–30 km | ~3,800 lone | 380–950 | no: costly, the field's spill covers it; camps through mechanism 6 |
| plant-eaters | | | | | no: the migratory kinds move by their flow already, and herds as groups were dropped on purpose |

Roamers only add accuracy where fields leave suitable country empty. So before adding any of these, measure the share of each kind's suitable land its field holds, and how fast an emptied region refills, on the same A/B worlds. Lynx is the first candidate, bear the second. Each is a one-line catalogue change once wolves and lions work.
