# Why the mountains are never better in summer (2026-10-15)

I measured the wide map (seed 1, 2000 km, year 3, commit ca25150) with `hex-seasons-by-height`. It shows, by height band and month, the means over all land in the band.

## What the model does

| Height | Snow gone | Green peak | July green | Soil water July (of what it holds) | July evaporative demand, mm/week | Warmth part of growth, July | Cured share, July |
|---|---|---|---|---|---|---|---|
| 0–300 m | Feb | 0.82 (May) | 0.23 | 0.12 | 32 | 1.00 | 0.88 |
| 700–1200 m | Apr | 0.69 (May) | 0.25 | 0.10 | 26 | 1.00 | 0.91 |
| 1200–1800 m | May | 0.58 (Jun) | 0.21 | 0.10 | 23 | 0.99 | 0.92 |
| 1800–2500 m | Jun | 0.44 (Jun) | 0.25 | 0.17 | 20 | 0.89 | 0.80 |
| above 2500 m | never (patches all summer) | 0.21 (Jul) | 0.21 | 0.48 | 17 | 0.41 | 0.28 |

- **Summer rain is the same at every height.** It is about 2 mm a week from June to September. There is no summer rain in the mountains.
- **Mid-height soils dry out like lowland soils.** Up to 2500 m the soil is full in spring and then empties in about six weeks against an evaporative demand of 20–26 mm a week. By July it is at 10–17% of what it holds, so the grass passes the curing line (half dry) and turns to straw. It stays straw until the autumn rains wet the soil fully again.
- **Above 2500 m there is water but no warmth.** Snow lies all summer and melt keeps the soil half full, but July there averages 6.6 °C. Growth runs from 2 °C to full only at 15 °C, so warmth is only 0.2–0.4 of full and the green peaks at 0.21.
- **Uplands lag the lowlands by about one month, then cure at the same time.** That one-month lag is the whole of the seasonal contrast the herds have to work with.

## What makes real upland summer pasture

Real high pastures (the yaylas of the Taurus and Zagros, the Lebanon and Hermon heights, the Caucasus) are grazed from June to September. They stay green for four reasons:

1. **The plants grow at the warmth they have.** Alpine and subalpine grasses and herbs grow fully at 5–10 °C. Plants are adapted to their place; a lowland grass's thresholds do not hold on a mountain. In the model one fixed curve (2 to 15 °C) applies everywhere, so it starves the coldest pastures of growth.
2. **They are perennials, not annuals.** Lowland Mediterranean grassland is mostly annual grasses that set seed and die as the soil dries; that is what "cured" models. Montane and subalpine swards are perennial bunchgrasses and herbs with deep roots. They slow down and partly brown in drought, then green again with any rain or dew. They do not turn to straw for the year at the first drying. In the model every place cures by the annuals' rule.
3. **Late snow and the water it leaves.** Snow and its melt feed the high soils and springs into June and July, and meltwater seeps out lower down along spring lines and wet hollows. The model melts snow by April or May up to 2500 m. The weather thread is working on seeps and meltwater.
4. **Cooler means less evaporation.** The model has this already: about 20 mm a week at 2000 m against 32 mm in the lowlands. It is not enough alone.

## What the model is missing

**1 and 2: plants adapted to their place.** Both belong to how plants grow, in `HexLand.stepPlants`, not to the weather:

- **Growth by local warmth.** The warmth a plant needs to grow fully would follow its place's warm-season normal: full growth at about 6–8 °C where the warmest month is around 14 °C, unchanged in the warm lowlands. With that, the land above 1800 m would grow strongly in July and August on its meltwater.
- **Perennial swards in the uplands and the cooler, wetter country.** These brown as the soil dries and green again when it wets, without locking into straw. Annual curing stays where the warmest month is hot and the summer dry: the Mediterranean lowlands and foothills.

Together these give the green wave the herds need. The lowlands are green in winter and spring and cure in May–June. The foothills follow a month later. The high pastures green in June and stay green into August or September, while the lowlands are straw. With protein tied to green, as now, herds and their young gain by climbing.

**3: summer water.** Late snow, seeps and meltwater are the weather thread's work and complement this.

No upland rule for the herds themselves is needed. They already seek green, and the protein rule and open uplands are in place.
