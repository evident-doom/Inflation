# Trade: what the record says, and what it means for the model

Notes from memory of the literature (authors named for following up). The figures are approximate.

## The record

**Goods moved far, and early.**
- Olorgesailie (Kenya, about 300,000 years ago): obsidian from 25–95 km and pigment brought in (Brooks et al. 2018).
- Upper Palaeolithic Europe: Mediterranean and Atlantic shells hundreds of km inland.
- Natufian: dentalium from both the Mediterranean and the Red Sea, Nile shells, a little Anatolian obsidian; basalt mortars and bowls.
- PPNA and PPNB: Cappadocian obsidian at Jericho and beyond, 700–900 km away; bitumen, greenstone beads, basalt vessels, late on malachite beads. The "PPNB interaction sphere" (Bar-Yosef and Belfer-Cohen).

**Archaeology cannot tell a gift from a trade.** A foreign object at a site came by one of three ways:
- **direct fetching**: the band went to the source itself, often on its rounds (Binford's embedded procurement);
- **down-the-line**: from neighbour to neighbour, drifting outward;
- **directional**: carried on purpose to particular places, by particular people.

Only the falloff with distance from the source tells them apart (Renfrew 1975). Anatolian obsidian in the Levant (Renfrew, Dixon and Cann) shows a supply zone of about 300 km, where people mostly fetched it themselves, and beyond it an exponential fall: down-the-line. Directional trade shows plateaus or second peaks at central places; the early Levant shows none.

**Mobile foragers exchange inside relationships.**
- **Hxaro** (Ju/'hoansi; Wiessner): lifelong gift partners up to some 200 km apart. Returns are delayed and kept unequal on purpose, so the bond never closes. It is insurance: when your ground fails, you go and live with a partner.
- **Demand sharing** (Peterson): the one who lacks asks, and refusal is costly.
- **Ceremonial exchange cycles** (Australia): Mount William greenstone axes, ochre from known mines and pituri travelled hundreds of km, swapped at gatherings.
- **Barter** is mostly between strangers or between different kinds of people: foragers with farmers (Mbuti and their Bantu-speaking neighbours), trade fairs on tribal borders, silent trade. No society is known whose economy ran on barter (Humphrey 1985; Graeber).
- **Money among foragers** comes only with settled, storing foragers: Chumash shell-bead money, Yurok dentalium, Northwest Coast debts and rank. These are the conditions of the Natufian.

So gift and trade are not opposites. Delayed gifts are the credit system, and barter is what is left for strangers: Sahlins' generalised, balanced and negative reciprocity, laid out by social distance.

## What the model has

| Record | Model |
|---|---|
| Local minerals fetched or given on | `TRADED` (good stone, salt, ochre, grinding stone) with deposits ([Bands.kt](../../src/main/kotlin/inflation/ecology/Bands.kt)); at a meeting a band gives what it holds beyond a year's keep to a band short of it, unless they feud (`FEUD_GIFT`) ([HexMeetings.kt](../../src/main/kotlin/inflation/ecology/HexMeetings.kt)). Anything held beyond the keep is given on again, so down-the-line flow should arise |
| Shell carried inland | `ornaments` and their `exotic` share, given between friends (`FRIEND_TIE`) along the ties |
| Makers for others | `spare` tools made by bands that make them well, given to bands whose kit falls short ([PeopleCrafts.kt](../../src/main/kotlin/inflation/ecology/PeopleCrafts.kt)) |
| Delayed return: a loan | `favours`: food given puts the taker in debt, in weeks of its need; returned when the creditor is hungry, and a return held back costs regard (`helped`, `given` in [CultureMeetings.kt](../../src/main/kotlin/inflation/ecology/CultureMeetings.kt)) |
| Standing from giving | `renown`, feasts |
| Gatherings | rite gatherings |
| Bulk stays near its source | carrying limits (`carryKg`) |

What the giving lacks: it is driven only by the giver's surplus, food alone creates a claim, and every gift is one-way.

## What trade asks of the model

### 1. One measure of worth

Only food makes favours. Stone, salt, ochre and tools win ties and renown but no claim on anything. The natural measure here is **person-days**: what a good would have cost the taker to get itself, the walk to the nearest source it knows plus the work there (a tool: its making days and materials). `BandTime` already counts days. A gift of salt then puts the taker in debt by the days it saved, and can be repaid in stone, food or tools. Worth differs between bands by where they live: a band beside the salt pan values salt at little, one 200 km away at much. That difference is the gain from exchange, and it comes from the geography without being set.

### 2. The side that lacks

Today only the giver chooses. Trade needs the band short of something to choose between:
- fetching it from a source it knows (a trip like a rite trip, by the shared reach search);
- asking the neighbours it meets anyway (demand sharing, what the model does now);
- going to a band it knows holds it, a band it is owed by first (as `visitKin` does for food).

The planner weighs these by days, as it weighs every other trip.

### 3. Barter with strangers

Gifts with favours need trust: a band will not give on credit to a band it may never see again. With strangers and weak ties the record has on-the-spot swaps, both ways at once, nothing left open. In the model: below a tie threshold, a band gives only against a swap of equal worth to itself at that meeting; above it, it gives on favours. Raids sit below both. Barter should then be found at the edges between peoples (culture regions, speech apart, foragers against farmers) and gift-credit inside them.

### 4. Goods worth carrying

Diversity is thinnest where the record is richest:
- **Obsidian** is folded into `STONE`, though the geology places it. It is the best tracer of the Levant record: light, sharp, and from few sources. Separate, it gives falloff curves to set against Renfrew's.
- **Bitumen** is in the geology and not a material, though it hafted tools as resin does (PPN).
- **Goods of one zone**: furs and hides from the cold and the highlands, dried fish from the coast and the great rivers, nuts and acorns from oak country. Seasonal and regional difference is the spine of insurance exchange, and the favours ledger already carries it.
- **Shell** is a material (`SHELL`) but moves only as ornaments.

Grinding stone and salt are bulky and should stay near their sources; obsidian and shell should travel furthest.

### Done so far

- Worth in days ([TradeWorth.kt](../../src/main/kotlin/inflation/ecology/TradeWorth.kt)): a kg of a traded good is worth to a band the walk to the cheapest source it knows and back with a load (`FETCH_LOAD`), plus the work there; a band that knows none reckons `UNKNOWN_SOURCE_KM`. A spare tool is worth its making and its good stone.
- Goods given at meetings make favours (`gaveGoods`), turned into food by the taker's own recent yield (`favoursOfDays`), and settle favours owed as food does (`helpedBy`). Seed corn and breeding stock make none. The `goodsFavours` key turns it off (0: the old rule).
- The census prints the goods given at meetings with their worth to the taker and to the giver (the price where they changed hands), and bands' holdings and reckoned worth by distance from the nearest source (the falloff).
- With `goodsFavours=0` a year on m5-1 runs line for line as before the change (2026-11-16).
- A/B of `goodsFavours` 0 against 1 (m5-1..3, 3 years, 2 replicates, 2026-11-16): no key number moves beyond chance (people within 0.01%, against the weather's own 0.01–0.74%); all runs within nominal limits. Goods make about 0.02 favours a day of their worth: the ledger fills, but nothing yet calls on it beyond food in hunger.

### What the first falloff shows

A year on m5-1 (the old rule): bands within 50 km of good stone hold about 96 kg an adult, at 50–100 km under 1 kg, beyond 100 km none, while what a kg is worth to them rises from 0.2 to 1.2 days. Salt and ochre fall the same way. So today's giving does not carry goods down the line beyond a band or two: a band gives only what it holds beyond a year's keep, and a band that has just been given a year's worth has none to spare. The record's falloff runs for hundreds of km; here it ends within about 100. Asking (step 2) and keeping a little more than a year's need where goods are dear are the likely ways to lengthen it.

### 5. Beyond food loans, toward money

In the order the record and the project's aim suggest:
1. favours made by any good, measured in days (1 above);
2. favours called in by the creditor asking, not only when it is hungry;
3. favours passed on: A, owed by B, asks B to give to C, to whom A owes;
4. a token standing for favours: shell beads (the `exotic` share), first among settled, storing bands, as with the Chumash and Yurok.

Hxaro's lesson for each step: an exact settlement ends the bond. Bands that keep balances open stay tied; strangers settle at once and stay strangers.

## Checks against the record

- **Falloff**: what each band holds of each traded good against its distance from the nearest source, by year. Down-the-line exchange shows an exponential fall beyond a supply zone of a few hundred km; directional trade shows plateaus. The coast against inland shell tally ([Tallies.kt](../../src/main/kotlin/inflation/tools/fauna/Tallies.kt)) is a first, coarse form.
- **Who exchanges how**: the share of exchange as barter, as gift on favours and as plain giving, by tie and by whether the bands share speech and customs. Barter should sit at the edges.
- **Balances**: how long favours stay open, and how many partners a band holds them with. Hxaro-like partnerships should be a few, long-lived and far apart; bands that lean on them should come through bad years better.
- **Sources**: obsidian and shell travelling hundreds of km in small amounts; grinding stone rarely beyond a few tens of km.
