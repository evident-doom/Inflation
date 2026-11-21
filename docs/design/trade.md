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

### Favours on a log scale

Chosen over fading (Olli, 2026-11-16): the favours a gift earns are logarithmic in its worth, so that big gifts count for more than many small ones (`t.logFavours`, 1; 0 the old rule). A gift worth `w` weeks of the taker's need earns `0.5 · ln(1 + w²)`: a week 0.35, ten weeks 2.3, a tenth of a week 0.005. Under a week gifts add little, so a big one outweighs many small ones; past a week each week more earns less, so there several middling gifts can still outweigh one big one (ten one-week gifts 3.5 against one ten-week gift 2.3). Wherever a debt turns back into what is owed (food to a hungry creditor, a kin visit's hopes, goods called in), it is the inverse: the gift that, given at once, would settle it (`weeksOfFavours`). Ledgers saved before are put on the scale once, each balance as one gift (`Band.favoursLog`).

- With `t.logFavours=0` a year on m7-1 runs line for line as main (2026-11-16). A/B on m7-1, 3 years, 4 replicates: people 0.00%, mixed; 2 of 27 key numbers the same way in all pairs, as chance gives; all runs with it within nominal limits.
- The ledger: some 8,500 favours outstanding against 41,600 (6.6 a band against 32); growing by about 150 a year against 1,100; the largest debt 10–15 against 270; the tenth most owed hold 38% against 47%. Still 87% of bands owed and 91% owing, some 16 partners each. It still grows, as goods still go one way; slowly now.

### What the first falloff shows

A year on m5-1 (the old rule): bands within 50 km of good stone hold about 96 kg an adult, at 50–100 km under 1 kg, beyond 100 km none, while what a kg is worth to them rises from 0.2 to 1.2 days. Salt and ochre fall the same way. So today's giving does not carry goods down the line beyond a band or two: a band gives only what it holds beyond a year's keep, and a band that has just been given a year's worth has none to spare. The record's falloff runs for hundreds of km; here it ends within about 100. Asking (step 2) and keeping a little more than a year's need where goods are dear are the likely ways to lengthen it.

### 5. Beyond food loans, toward money

In the order the record and the project's aim suggest:
1. favours made by any good, measured in days (1 above);
2. favours called in by the creditor asking, not only when it is hungry;
3. favours passed on: A, owed by B, asks B to give to C, to whom A owes;
4. a token standing for favours: shell beads (the `exotic` share), first among settled, storing bands, as with the Chumash and Yurok.

Hxaro's lesson for each step: an exact settlement ends the bond. Bands that keep balances open stay tied; strangers settle at once and stay strangers.

## What flows back: wealth

Goods go one way, down the line from the sources, and the favours they make were never paid back. Something has to flow the other way, and the record says it is mostly not like goods: work feasts (Dietler and Herbich; Göbekli Tepe's enclosures read as built by gatherings fed with meat and likely beer), potlatch and *moka* (great gifts make rank, called in as support and labour), counterflows of unlike things (*kula* necklaces one way and armshells the other; Australian axes one way and *pituri* the other; marriage partners one way and gifts the other), and debt that cannot be repaid in kind becoming service (Graeber). That counterflow, turned into things that last, is a candidate for how early wealth gathered.

### Possession, not property

There is no property in the world but possession (Olli, 2026-11-16). A band owns what it holds in hand or occupies now: what it carries and stores, its herds and dogs, its crafted goods, the structures it is using. A house left behind is whoever's is in it next; getting it back takes force: there is no authority, law or register in the world. The model's lists (a structure's builder) are its own bookkeeping, not claims the people hold. Debts are memory, enforced only by regard, grudges and raids. Standing (renown, fame, ties, credit) is what others remember of a band, not something it holds, and is measured apart from possession. Monuments and decoration belong to no one: their fame goes to the builders, their holiness to the place.

### The plan

1. **A census of wealth**: for each band what it possesses (food and goods stored, herds and dogs, ornaments, the structures it uses now) and its standing (renown, fame, credit net of debt), each with how unequal it is across bands (Gini, the top tenth's share), and how it differs at the sources and away from them. Report only: today's spread, to measure the rest against.
2. **Crafted goods as stored labour**, held and worn: ornaments told apart by their worth in days, the skilled days that went into them and their rare materials (shell beads quick; drilled stone and greenstone beads, carved bone and figurines slow; obsidian pieces from few sources; later copper and malachite), made better by skilled hands, so that specialists have a reason. Durable and portable: great gifts (on the log scale one fine gift outweighs many trinkets), grave goods, marriage gifts, robbed in raids, later tokens. Standing by the worth of what a band holds and wears, not by count.
3. **Labour as the counterflow**: a debtor repays in its people's days of work for the creditor, at the creditor's camp, into projects of three kinds: useful (houses, weirs, wells, pens), ceremonial (halls, and monuments or enclosures at rite sites with no use but holiness, rites drawn and fame), and decoration (plastered and painted walls). Great projects as work feasts, the host feeding the workers from its larder: stored surplus turned into monuments, and debts into fame. What the work builds is the creditor's only while it stays there, so settled, defended camps at rich places gain by it.

### The first census of wealth

Three years on m7-1 (main, 2026-11-16; Gini across bands; the tenth best-off bands' share):

- **Possessions are fairly even.** Food stored 15–19 days of need (Gini 0.34–0.47, top tenth 23–27%); goods some 250 kg a person (0.19–0.21, 22%); ornaments 2.2 a person (0.16, 16%); houses in use shelter 56% of people (0.37–0.39, 22%). Kept herds and dogs are nearly none, and no band uses a hall, weir, well or pen.
- **Standing is not.** Renown Gini 0.60 (top tenth 44%), credit 0.79 (62%), fame 0.85 (72%).
- **The sources gain in standing, little in possessions.** The 87% of bands within 50 km of good stone, salt or obsidian are creditors (+1.7 favours a band) and the rest debtors (−2.9); they have half again the renown (47 against 32), three times the fame, twice the share housed (0.59 against 0.28), and more goods (260 kg a person against 180); their food stores are as large or somewhat larger (15.7 against 8.0 days in one year, 19.1 against 19.0 in another).

So the one-way flow already gathers credit and standing at the sources, but nothing turns it into possessions: there is no counterflow. Possessions are no more unequal than foragers' (crafted goods are one kind, all alike). That is what steps 2 and 3 are for. (50 km takes in most bands; the next census should ring them by distance, as the falloff does.)

### Crafted goods (step 2)

Ornaments are made of finer kinds by skilled hands and are worth their days (`t.craftWorth`, 1; 0 the old rule): stone figurines (8 days, 0.3 kg of grinding stone) and drilled stone beads (6 days) pecked and carved, obsidian pieces (4 days, 0.1 kg), shell beads and bone pendants (2 days, as all ornaments took before). A band makes the finest kinds its materials and hands allow (a least skill at the craft), then the plainer, up to three a person; each is worth its days times the skill of the hands (`Band.ornamentWorth`). Standing counts by worth; ornaments given carry their worth and make favours by it on the log scale; graves and offshoots take worth with the pieces. Rare materials only open the kinds: pricing them would ask searches of the ground.

- With `t.craftWorth=0` a year on m7-1 runs line for line as main (2026-11-16). A/B on m7-1, 3 years, 4 replicates: people +0.12%, mixed, inside the weather's 4.4%; 1 of 27 key numbers the same way in all pairs (hippo +0.2%); all runs with it within nominal limits.
- Ornaments' worth a person rises from 4.4 to 5.4 days in three years, but hardly grows less even: Gini 0.16 to 0.17, the top tenth's share 16% to 19%. Nearly all the making is figurines (195 band-weeks of it a year, against 38 of pendants, 11 of shell beads, 7 of obsidian and 3 of drilled beads): grinding stone lies by 98% of bands and the pecking hand is common where grinders are made, and a band stops at three a person, so it makes only to replace what it gives and buries. Fine goods are neither scarce nor few hands' work.

What would let crafted wealth part bands: skill as a specialist's (a least skill only few reach, kept by practice: the finest kinds taught within a band and lost without makers); materials that are truly rare (obsidian, shell inland, later copper; greenstone, which the geology does not yet tell from grinding rock); and a band making for worth, not up to a count, when it has time and the will to it, so that those with spare days and hands store their surplus as finery, and finery is worn out, buried and given away at the rate the record shows.

### Finery: a master's hands, made for worth, of leisure, and broken (step 2, continued)

With `t.finery` (1; 0 the previous step): the fine kinds ask a master's skill (figurines 0.85, drilled beads 0.75, obsidian 0.65, against 0.6, 0.5 and 0.5), and making them practises the craft, so the skill is kept by making and lost without. Beyond the plain ornaments, a band with such hands and the material makes finery for its worth, up to 30 person-days of it a person, but only of its leisure: at most half the week's rest still unspent when the chores are done, at most 0.2 person-days a person a week, and none for a band that ate under 0.9 of its need (`fineryLeft`). Ornaments are worn out, broken and lost at a tenth a year (`wearOrnaments`): a sink beside graves, so that wealth settles where making meets loss. The census now counts the worth made, broken, buried and given each year.

- Made from days the week asked for, finery cost people 2.2% on m7-1, lower in every pair: it took days from rest. Of leisure only, people −0.19%, mixed, inside the weather's 15.7%.
- With `t.finery=0` a year on m7-1 runs line for line as main (2026-11-17). A/B of 0 against 1 on m7-1, 5 years, 4 replicates: 4 of 27 key numbers the same way in all pairs (about 3 by chance), most leopards +4.3% (they have gone either way in these A/Bs: −5.5% with obsidian); all runs with finery within nominal limits.
- Ornaments' worth a person 7.4 days against 5.9 after five years; its Gini rises year on year, 0.19, 0.24, 0.28, 0.29, 0.30 (0.19 without), the top tenth's share 26% against 20%. Bands within 50 km of a source hold 7.7 days a person, those further 5.3. In the fifth year 1.26 days a person were made, 0.73 broken and 0.26 buried: near the level where making meets loss. Gifts move nearly as much as is held each year (6.9 days a person), the circulation that keeps finery from gathering faster.

### Labour for favours (step 3a)

A band whose house and hall building ran short of days last week, and that has food, calls on the bands within two days' walk that owe it (`t.labourFavours`, 1; 0 none; [TradeLabour.kt](../../src/main/kotlin/inflation/ecology/TradeLabour.kt)). Each pledges people for as many days as it owes, as fully as it repays, a day an adult a week at most; they walk there at once, and the days are held for the creditor's building next week (`BandTime.lend`), what it leaves taken back so it never becomes the creditor's rest (`reclaim`). At the next call the labour is settled as far as it was built with: the debtor pays those days, the creditor feeds the workers for them (a work feast, the food to their band), and the debt shrinks by their worth to the creditor on the log scale. What is built is the creditor's only while it stays.

- Paid as pledged, 60% of the days went unused and labour cost people 1% in every pair; settled as built with, people −0.07%, mixed (m7-1, 5 years, 4 replicates, 2026-11-17). With `t.labourFavours=0` a year runs line for line as main.
- It is a trickle: 0.17 person-days a person a year pledged, 0.08 built with, 0.11 kg of food a person fed, some 16 bands a year; housing and credit do not change. Houses are cheap and bands mostly finish their own; labour waits for projects too big for one band (3b).
- One run with labour broke a nominal limit on cheetahs (6 of 47 lost unaccounted for in year 5, against 10%); taken as chance in a kind of few heads that swings 50% with the weather, but noted.

### Monuments and adornment (step 3b)

A settled band whose home ground is holy (sanctity 20 or more, from its dead and rites) raises a monument there, a shrine, tower or enclosure (`t.ceremony`, 1; 0 none; [Ceremony.kt](../../src/main/kotlin/inflation/ecology/Ceremony.kt)); on ground not yet holy it adorns the houses it lives in. Both are of its rest only, done last in the week after every chore, and of the days its debtors lend it. A monument wants a day a person a week, more than a band's rest gives, and what it cannot have becomes its building's shortfall, so its debtors are called to build and fed at its feast: labour at last has projects too big for one band. A monument is set of 20 kg of stone a person-day, but only of stone beyond the band's keep for its tools and its stock to give on; it weathers 2% a year and belongs to no one, its work adding holiness to the place and a little renown (0.01 a person-day) to the builders. Adornment weathers 20% a year.

Labour was made to cost the debtors their rest only: they come only when they ate their fill, send at most half the rest their last week left them, and pay the work and the walk from the rest their weeks leave them, after every chore (`Band.labourOwed`).

- The cost to people over the fixes, on m7-1, 5 years, 4 replicates (2026-11-17): 7.9% at first (labour asked whatever the stone, three quarters of it unused; hungry debtors came); 4.3% with labour bounded by stone and hungry debtors at home; 3.5% with debtors' labour of their leisure and ceremonial renown a fifth (renown had doubled; it still did, for the stone); 2.3% with monuments of surplus stone only (6,000 t of stone had gone into them in five years, and the share of bands with spears fell below its floor); 1.5% with debtors paying of their rest; 0.36% (−1.1% to 0.0%) with ceremonial work last in the week, of rest left after every chore. 4 of 27 key numbers then move the same way in all pairs, as chance gives; all runs with it within nominal limits.
- What it makes: some 280 monuments standing after five years, 290,000 person-days of work in them, the largest 9,700; 350 houses adorned; 1.6 person-days a person a year of monument work of the builders' own rest, and 1.4 of debtors' labour from some 200 bands a year, fed at 1.8 kg a person a year. Renown stays as it was (49 against 45), higher at the sources (52 against 29 further).
- The labour rules alone (labour off against on, no ceremony): people −0.65%, mixed; labour a trickle without monuments (0.12 person-days a person a year pledged, 0.06 used).

### Art: goods of perceived worth (2026-11-21)

Olli (21 Nov): decoration and art as a general use of free time, so that bands far from the sources can give things of like worth back, and worth measured beyond survival (the needs tiers' standing tier). Design: Claude Doc "Art and goods of perceived value". Step 2 built (`t.art`, 1; 0 none of it; [Art.kt](../../src/main/kotlin/inflation/ecology/Art.kt)):

- **Worth to the taker.** A piece's worth to the band that takes it is its days of skilled work times 1 + `ART_STRANGE` (1) times its strangeness: the share of the four style traits ([Band.style], already copied at meetings and drifting) in which the pieces' style is not the taker's own. Things from far away carry worth because they are far (Helms 1988); bead styles mark peoples and are read by strangers (Vanhaeren and d'Errico 2006; Kuhn and Stiner 2007). From memory, to check.
- **Pieces keep their makers' style.** Each band keeps the worth-weighted mix of styles its ornaments were made in (`Band.artMix`); its own work goes in in its own style, gifts carry the giver's mix, joins blend by worth.
- **Art of common materials.** Painted hides, carved wood, carved bone and antler, fine cordage and baskets (`OrnamentKind`), made as finery is, of leisure only and by a master's hands, of what the band holds beyond a set of every tool it knows (`artKeepKg`). Wood, hide and fibre pieces wear out at `SOFT_WEAR` (0.3 a year) against 0.1.
- **Debts paid in art.** A band that owes the other (tie at least `FEUD_GIFT`) gives ornaments to settle it, as many as at their worth to the creditor meet the debt, at most half of what it holds, as fully as it repays (`artRepaid`). Every ornament gift makes favours by its worth to the taker.
- The census adds, under ornaments' worth: given to repay, and worth to the takers beyond their days.

## Checks against the record

- **Falloff**: what each band holds of each traded good against its distance from the nearest source, by year. Down-the-line exchange shows an exponential fall beyond a supply zone of a few hundred km; directional trade shows plateaus. The coast against inland shell tally ([Tallies.kt](../../src/main/kotlin/inflation/tools/fauna/Tallies.kt)) is a first, coarse form.
- **Who exchanges how**: the share of exchange as barter, as gift on favours and as plain giving, by tie and by whether the bands share speech and customs. Barter should sit at the edges.
- **Balances**: how long favours stay open, and how many partners a band holds them with. Hxaro-like partnerships should be a few, long-lived and far apart; bands that lean on them should come through bad years better.
- **Sources**: obsidian and shell travelling hundreds of km in small amounts; grinding stone rarely beyond a few tens of km.
