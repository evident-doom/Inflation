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

- Asking ([TradeAsking.kt](../../src/main/kotlin/inflation/ecology/TradeAsking.kt), step 2): each week a band short of a traded good may send carriers to a band it has met within six days' walk that holds more than its year's keep, where the goods it can hope for are worth more of its days than the carriers' walk there and back. `t.askForGoods=0` turns it off. Fetching was already there: bands that hold their home send task parties to sources they remember ([TaskTrips.kt](../../src/main/kotlin/inflation/ecology/TaskTrips.kt), Binford's collectors), and moving bands gather along the way.
- With `t.askForGoods=0` a year on m5-1 runs line for line as before (2026-11-16). A/B of 0 against 1 (m5-1..3, 3 years, 2 replicates): people within 0.08%, inside the weather's own swing; one of 31 key numbers (striped hyena, +0.1–0.2%) moved the same way in all pairs, as chance gives; all runs within nominal limits. On m5-1 about 70 bands a year go asking, and stone given rises by about a quarter; the falloff hardly moves (still none held beyond 100 km of a source).

Why asking does not lengthen the falloff: the bands far out have rarely met a band that holds goods to spare, and those that have are mostly more than six days off. Goods reach far only through bands in between keeping more than their own year's need, which none does. That is the next lever: a band that has goods dear to it keeps more than a year's worth, or passes on what it was given beyond its keep for the regard it wins.

- Giving on (`t.passOnYears`, 1; 0 the old rule): a band takes in a year's stock of good stone, salt and ochre beyond its own keep, to give on; what it can spare is still reckoned against its own keep, so the stock is what it gives on (`lackGoods`). It takes the stock only from a band that holds the good cheaper, nearer the source (`stockFrom`). Without that rule the stock went round among neighbours who held it as dear: ten to fifty times the goods given (m5-1 salt 40 t to 1,800 t a year), at the same worth to both sides, each gift making favours for nothing.
- With `t.passOnYears=0` a year on m5-1 runs line for line as before (2026-11-16). A/B of 0 against 1 (m5-1..3, 3 years, 2 replicates): no key number moves the same way in all pairs; people +0.12%, −0.13%, +0.02%, within the weather's swing; all runs within nominal limits.
- The falloff lengthens, a little. Held an adult at 50–100 km, without and with: on m5-1 stone 0.68 → 1.00 kg, salt 0.34 → 0.80, ochre 0.29 → 0.44. Beyond 100 km, where there was none: m5-2 salt 0.81 kg and stone 0.16 at 100–200 km; m5-3 ochre at 100–200 km 0.54 → 0.78. Goods given rise two to five times, and now go to bands that value them more (salt on m5-1: 0.26 days a kg to the taker against 0.23 to the giver). Stone still hardly passes 100 km: a person's year of it is 1 kg, so a stock of it is small beside the walk to fetch it.

- Obsidian (step 3, `t.obsidian`, 1; 0 none is got): its own material, from the geology's obsidian by explosive vents (`HexLand.obsidian`), gathered along the way at flint's rate, 0.2 kg a person a year. Stone tools are knapped of it before flint, and the share made of it (`Band.glassy`) serves `OBSIDIAN_EDGE` (0.1, an estimate) better. It is traded, asked for and given on as the others are; a house at a source is famed for it.
- With obsidian off it stays out of trade altogether (`tradedNow`): off but still in the traded list, its worth was reckoned for every band, and those extra searches of the ground sent a year on m7-1 another way from the first months (bisected 2026-11-16). With that, `t.obsidian=0` runs line for line as main.
- A/B of 0 against 1 on m7-1 alone (the m7 worlds published so far), 3 years, 4 replicates: people +0.01%, mixed; 8 of 27 key numbers moved the same way in all four pairs (about 3 by chance), most leopards, −5.5% (−2.9 to −8.2, against the weather's 12%), likely as sharper spears and bows near the sources take more of their game; all runs with obsidian within nominal limits (two base runs fall outside on oryx fed and mast, as main does).
- Obsidian's falloff, held an adult by distance from a source: under 50 km 4.75 kg, 50–100 km 0.13, 100–200 km 0.04, beyond 200 km none; a kg's worth rises from 0.34 days to 1.27. It travels further than stone, but falls about thirtyfold within 100 km, far steeper than Renfrew's supply zone of some 300 km. The record's obsidian went further on less: a band's need of it is small, and nothing yet makes a far band want more of it than its year's keep and stock.

- Calling in favours in goods (toward money, step 2; `t.callInFavours`, 1; 0 the old rule): a band that owes favours to a band lacking goods gives it, of what it can spare, no less than it owes, as fully as it repays (`repayWill`); the debt in the creditor's days, each good at the creditor's worth, so a debt made by salt can be paid in stone; a return held back costs regard, as with food. At meetings and when asking, and an asking band goes to its debtors first ([TradeFavours.kt](../../src/main/kotlin/inflation/ecology/TradeFavours.kt)).
- With `t.callInFavours=0` a year on m7-1 runs line for line as main (2026-11-16). A/B on m7-1, 3 years, 4 replicates: people −0.35%, mixed, inside the weather's 1.9%; 3 of 27 key numbers the same way in all pairs, as chance gives; all runs with it within nominal limits.

### The ledger nobody pays off

The new census of who owes whom shows what goods' favours have made since step 1: on m7-1 some 45,000 favours outstanding, 35 a band (eight months of a band's need); 93% of bands are owed and 94% owe, each with some 20 partners; the tenth most owed hold 47% of the credit; the largest single debt is 300 favours, six years of a band's need. It grows by some 1,600 favours a year (32.5, 33.6, 35.1 a band over three years), and calling in, some 350 repayments in goods a year, hardly slows it (45,364 with it against 44,992 without).

The cause is the direction of the goods: they go down the line, away from the sources, year after year, and a band near a flint source has nothing to ask of the bands further out; favours never fade and are settled only by help coming back. Yet the debts change little: a hungry creditor is given food up to its need by a band that owes it, which with debts this size is nearly every time.

What a debt should be is a design question before the next step: favours that fade as ties do (hxaro partners keep balances open on purpose, but not for ever); goods' favours worth less than a week of need for a day of work spared; debts called in as labour, feasts or brides rather than goods; or debts passed on to a third band, the step toward money, which a one-way ledger like this would make worth doing.

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
