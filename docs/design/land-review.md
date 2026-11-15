# Land review: plants, fire, soil, water and fields

The fifth review, of the land's own part of the week: `HexPlants`, `HexFires`, `HexHydrology`, the soil's life (`SmallLifeSoil`) and where the bands' fields meet them (`HexFood`, `PeopleFarming`). Trade was left out, as another session is working on it.

The water is the most careful part. Its rates go by the days of the step (`1 - exp(-rate × days)`), it keeps its own books, and the sunny and shaded slopes each have their own soil water. The frays are elsewhere: in what a field does to the land, in how many soils there are, and in a few plant rules that go by the step rather than by time.

## 1. A field is cleared on paper only

`HexFood.cleared` is "the share of each place's land cleared of its trees and scrub for fields". Clearing it costs the band its days (`clearDaysPerHa`), and the clearing grows back over `REGROW_YEARS` (12) when it isn't cropped. But nothing takes the trees or the scrub off the place. `HexPlants.tree` and `shrub` stand as they were on cleared ground, and so do everything read from them:
- the canopy that shades the grass;
- the cover game hides in and stalkers use;
- the fuel a fire burns;
- the browse;
- the wood people gather.

The clearing's regrowth runs on its own 12-year clock rather than on the plants' own growth from seed.

So a farming hex in the forest is still a forest to everything but the field. The historical record runs the other way. Early farming opened the woods (the pollen of the Levant and of Neolithic Europe shows the trees falling back and open-ground weeds coming in), and the opened ground drew game that likes edges and drove off the game of the deep woods.

**Fix:** clearing takes its share of the place's shrubs and trees off, as people's gathering of wood already does (`plants.consume`). The wood felled goes to the band's wood, or is burnt where it lies (it was mostly burnt). Then the cleared share is simply what the plants say: drop `cleared`'s own regrowth and read it from the woody cover left, or keep `cleared` only as the field's footprint and let the plants regrow on it by their own rules once it is left. This changes results, so it needs an A/B.

## 2. Three soils that don't meet

The land's fertility is kept in three places:
- **`HexPlants.fertility`:** the bedrock's soil (`soilFertility`), raised by ash and fire, worn by erosion on bare slopes, and back to its natural level over `SOIL_RECOVERY_YEARS` (50). The plants' ceilings read it.
- **`HexPlants.organicFertility`:** set each step by the soil's life from its organic matter (`SmallLifeSoil`). The ceilings read it too.
- **`HexFood.fieldSoil`:** a field's own wear. Cropping again lowers it toward `CROPPED_FLOOR` (0.45) by `CROP_WEAR`, and rest mends it by `REST_MEND`.

The sown crop reads only the third. Its expected yield goes by the seed's domestication, the season, the water it went short of and the field's wear (`fieldExpect`, `cropSoil`), never by the ground it is sown on. Grain sown on thin hillside soil expects as much as grain on the alluvium of a valley floor (`HexLand.floorFertility`, which the plants do read). And a field cropped out wears only itself: the place's plants never feel it, and when the field is left they grow on the place's own fertility as if it had never been cropped.

The first farmers chose their ground by its soil: the alluvial fans and spring-fed basins of the Levant (Jericho, Netiv Hagdud, Gilgal), and later the loess of Europe. A model in which the soil doesn't matter to the crop can't show that choice.

**Fix:** one soil for the place. The crop's yield reads the place's fertility as the plants do (`0.3 + 0.7 × min(1, fertility + organic)`, on the valley floor its floor fertility), times the field's wear. The field's wear stays its own, a share of the place, so a band that moves its field finds fresh ground. Whether cropping should also draw down the place's `fertility` can wait: the wear already covers what a few years of cropping do. This changes results: A/B.

## 3. Plant rules that go by the step

The land runs in ticks of `daysPerStep` days (6 by default; any divisor or multiple of 30 is allowed). Most of `HexPlants.step` scales by `years`, but some rules take a fixed share each tick, so the same weather does more harm on short ticks than on long ones:
- **Frost kill:** a tick whose mean temperature is below a form's `frostKill` takes 70% of it (`frostAndFlood`), whatever the tick's length.
- **Floods:** a tick in flood drowns half the floodplain's grass and a tenth of its scrub, likewise.
- **Trampling:** each tick beats the grass down by `exp(-trampleHerb × trampling)`, with the residents' trampling set once a step and taken again each tick (a documented choice, but still a share per tick).
- **The plants' warmth:** it rises halfway to a warmer tick at once (`0.5f`), while it falls by `memory`, which does scale.
- **Seed sources:** they are worked out afresh every 5 ticks (`stepCount % 5`): every 30 days at 6-day ticks, every 150 at 30-day ones.

**Erosion goes as the square of the tick.** It multiplies the tick's precipitation (mm in the tick) by `years` (the tick's share of a year), against "50 mm of rain a week". In steady rain, a year's erosion comes out as `days / 7` times the figure it was set for: 6/7 at 6-day ticks, and five times that at 30-day ticks.

**Fix:** each of these as a rate a day:
- frost and flood as a daily kill rate over the tick's days;
- trampling's beating down as a rate a day;
- the warmth's rise with its own memory of days;
- seed sources by days (every 30), not by ticks;
- erosion on the rain a day, `precipitation / days × 7` against the 50 mm a week it was set for.

At the default 6-day tick some of these move results (frost and flood least, being rare; erosion rises by 7/6 at 6-day ticks), so this needs an A/B. Better still is a check that a year comes out the same at 6- and 30-day ticks, within the weather's noise.

## 4. Frost doesn't know where the plants grow

Plants are adapted to their place in growth: where summers are cool, they grow fully at less warmth (`growFrom`, `growSpan`), and the sward is perennial. But they aren't adapted in frost. `frostKill` is one figure for each form everywhere (herbs −45 °C, shrubs −30, trees −25, as a tick's mean), so a tick at −26 °C takes 70% of a taiga's trees. Siberian larch and the steppe's perennial grasses stand far colder.

At the Levant's and Europe's temperatures this never bites, but any colder world would lose its forest every hard winter. **Fix:** the frost a place's plants stand follows its coldest month, as the growth follows its warmest. Unchanged where winters are mild, so it may well leave today's results as they are. Run it with item 3.

## 5. A second plant and water model kept for one check

`Vegetation` (342 lines, per fine cell) and `LiveWater` are used only by `tools/HexLandCheck`. That tool runs them beside `HexPlants` and `HexHydrology` to compare. But `HexPlants` has "since gone further" (its own words) with:
- cured grass weathering;
- seeps;
- the wet valley floor;
- grass maturity;
- shoots for browsing;
- trodden seedlings.

So the check now compares the model with an old copy of itself, and its differences say nothing about either. `LifeForm` lives in Vegetation.kt, and `canopy` is written in both.

**Fix:** move `LifeForm` to `HexPlants`, and delete `Vegetation`, `LiveWater` and the check, or cut the check down to the timing it also gives. It changes no result.

## Smaller things

- **Fire and water:** fire spreads between neighbouring places through `canBurn` alone, so a river between them doesn't stop it (only a flooded place doesn't burn). A big river would be a firebreak. This is minor; it belongs with any fire work.
- **Burnt fertility:** `HexFires.burn` adds `0.01 × share` to fertility, and `HexPlants` walks it back over 50 years. That's a small figure from memory, worth a source if fire is looked at again.

## Order

1. Item 5: no result changes, so it can be checked line for line.
2. Item 3 with item 4: one A/B, plus the 6-against-30-day check.
3. Items 1 and 2: one A/B, since both are where the fields meet the land, and the clearing's wood and the soil's yield together decide where a band sows.

## Done

Each item on its own branch and A/B, three worlds by two replicates over three years.

- **Item 5:** `Vegetation`, `LiveWater` and `HexLandCheck` are gone, and `LifeForm` is in HexPlants.kt; the fine `Weather` stays for `HexWeatherCheck`. One year on m5-1 is the same line for line.
- **Item 3:** frost, floods, trampling and the plants' warmth go as a rate a day, set at the 6-day tick (`SET_AT_DAYS`), so they come out the same there. The seed sources are worked out every 30 days, and erosion reads the rain a week. A/B: no key number moved the same way in all pairs. (The 6- against 30-day check is not run: a 30-day tick makes the worlds afresh, which is left to the overnight runs.)
- **Item 4:** plants stand a tick's mean down to `FROST_MARGIN` (15 °C) below their place's coldest month, or their form's own `frostKill` where that is colder. A/B against item 3: m5-2 and m5-3 the same, m5-1 moved in nothing the same way in all pairs.
- **Item 1:** `HexPlants.opened`, set from `HexFood.cleared`. Newly cleared ground has its shrubs, trees and shoots cut and left as litter, and the woody ceiling is less by the share kept open. A/B on the farming era (`era=ppnb`; at the default era no band sows in three years, so it moved nothing): wolves −0.3% in all pairs, nothing else.
- **Item 2:** `HexPlants.soil`, the one soil the plants' ceilings and the fields read; a field's cereal bears by it, times its own wear. A/B on the farming era: grain reaped on m5-1 −18%, people −0.2 to −0.5%, the big grazers −0.01 to −0.1%, in all pairs. The yields per kg were for rested ground; they now hold on the best soil only.
- **Fire and rivers (smaller things):** a fire front doesn't cross a border with a river `FIREBREAK_M` (30 m) wide or more. A/B: the same in every number, as no fire in these worlds reached one.
