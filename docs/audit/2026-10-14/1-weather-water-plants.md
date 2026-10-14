# Audit 1: the physical layer (weather, wind, water, plants, eruptions, plant food)

Scope: `world/Weather.kt`, `world/Wind.kt`, `world/HexLand.kt`, `world/LiveWater.kt`, `world/HexWorld.kt`, `world/Volcanism.kt` (Eruptions), `ecology/HexFood.kt`. I also read the constants they borrow from `Vegetation.kt` (LifeForm, the *_SCALE values), `Rivers.kt` (Channel), `Calendar.kt`, `Climate.kt` and the wiring in `ecology/HexSim.kt`.

Calendar facts the findings depend on (`Calendar.kt:14-27`): day 0 of the year is the winter solstice, `month` is 0-based, a run starts on day 90, and `Climate.WARMEST_DAY = 210`.

At runtime the hex model uses `HexLand`, not `LiveWater`. `LiveWater` is only built by `tools/HexLandCheck.kt`, but `HexLand` borrows its `SOIL_CAPACITY`, `RECHARGE` and `BASEFLOW`.

---

## 1. Rules

| # | Mechanism (file:line) | Verdict | Reason |
|---|---|---|---|
| W1 | Weekly temperature and log-rain anomalies are AR(1) noise on a 50 km coarse grid, kept for about 5 days and smoothed over 3x3 cells (Weather.kt:187-189, 353-365) | rough | The persistence is right. Temperature anomalies decorrelate after about 100-150 km, but real synoptic temperature anomalies span about 1000 km. Variance is the same in every season. |
| W2 | Step temperature = monthly normal interpolated by day of month + anomaly + shock + regime warmth − volcanic cooling (Weather.kt:195-204) | rough / **bug** | The normals are mid-month values (Climate.kt:65, `m*30+15`), but the interpolation starts at day 1, so the whole seasonal cycle runs about 15 days late. |
| W3 | Step rain = monthly normal × share of the month × storminess × orographic factor ÷ seasonal norm × lognormal anomalies (Weather.kt:205-206) | rough | No week is ever dry. Rain is a smooth multiplier, but real Mediterranean weekly rain is very intermittent (many zero weeks inside the wet season). This hides dry spells and false starts and smooths the soil moisture that drives fire, pools and greenness. |
| W4 | `RAIN_BIAS` keeps lognormal rain at a mean of 1 (Weather.kt:386) | **bug (small)** | It assumes a year-to-year sigma of 0.3, but `yearAnomaly` uses 0.25 (line 283). Rain is about 1.4% low. The oscillation and decadal log terms (lines 319-320) and the drought shocks get no bias correction at all. |
| W5 | Year anomaly AR(1) φ 0.4, σ 0.25; a 5-year AR(2) oscillation; a decadal AR(1) at 0.9 (Weather.kt:282-288) | rough | Interannual rain CV comes out about 0.3 everywhere. Real CV rises steeply with aridity (about 0.2 in humid hills, 0.5-0.7 in the Negev and Sinai, from memory), so the deserts are far too reliable. |
| W6 | Multi-year drought: 1.5%/yr for the whole map, 2-8 yr, 200-500 km, rain ×0.55-0.75 (Weather.kt:292-296) | rough | It is plausible as the tail of the distribution, but its rate is set per map rather than per area. |
| W7 | Drought year: 12%/yr per map, "about one year in eight in any one place" (Weather.kt:297-300) | arbitrary / mis-stated | With a Gaussian footprint of R≈500 km on a 2×10⁶ km² map, one place gets w>0.5 only about once in 30 years, not once in 8. The rate is per map, so changing the map size changes the local drought frequency. |
| W8 | Severe winter starts at `day + 270..340` and heat wave at `day + 100..160`, where `day` is the start of the year (Weather.kt:303, 308) | **bug** | `newYear()` runs at day 0 (the solstice) from year 1 onwards. Cold snaps therefore land between mid-September and late November, and heat waves in April to early June, not around day 210. Only year 0, which starts on day 90, gets them in the right season. |
| W9 | Shocks: drought adds +0.8 °C, a heat wave cuts log-rain by 0.7 (Weather.kt:325-326) | rough | The sign is right (dry soils warm the air), but the 0.8 is a fixed number rather than an effect of soil moisture. |
| W10 | Volcanic cooling: VEI 5 → 0.4 °C, VEI 6 → 1.0 °C, uniform over the whole map, e-folding over 360 days (Weather.kt:165-170, 190) | rough | Cooling scales with sulphur, not VEI. Pinatubo (VEI 6) cooled about 0.4-0.5 °C globally; most VEI 5 eruptions cooled less than 0.2 °C (from memory). |
| W11 | Snow when the weekly mean is below 0 °C; degree-day melt at 4 mm/°C/day (Weather.kt:269-276) | grounded (melt) / rough (phase) | 2-6 mm/°C/day is the textbook range. An all-or-nothing switch at 0 °C on a weekly mean is crude: snow falls on days below about +1 °C even in weeks averaging +2 °C. |
| W12 | Regime persistence 0.5, otherwise a draw by seasonal weight (Weather.kt:215-219, Wind.kt:50-60) | grounded | It keeps the stationary distribution equal to the weights, and the mean length of about 12 days matches the usual 5-15-day regime lifetimes. |
| W13 | Jet latitude 42 ± 6° with seasonal meander (Wind.kt:63, Weather.kt:220-222) | rough | It is plausible for the eastern Mediterranean storm track. |
| W14 | Base wind of each regime (Wind.kt:69-80) and its warmth (Wind.kt:86-95), with the seasonal mean of warmth removed | rough | The signs are physical. The magnitudes are hand-set. |
| W15 | Orographic factor: exp(0.01·rise − 0.0008·barrier), clamped to 0.2-4 and scaled by speed/5 m/s (Wind.kt:105-115, 141-164) | rough | Forced ascent and rain shadow are the right processes. The clamp, the 150 m/km rise cap and the (1 − km/100) distance weight are hand-set. It is normalised back to the climate normals, so it only redistributes rain between regimes. |
| W16 | The seasonal norm uses base winds only, with no eddies or meander (Wind.kt:118-127) | rough | There is a Jensen bias: eddies change the mean of `rainFactor`, so local means drift off the normals. |
| H1 | Soil bucket: rain and melt in, ET = PET·min(1, s/150), excess above 150 mm → 40% to groundwater and 60% to the river (HexLand.kt:261-275) | rough | Only saturation-excess runoff. No infiltration-excess (Hortonian) runoff, which dominates arid Levant wadis. The same 150 mm everywhere. PET is the **monthly climatological normal** (HexLand.kt:43, 268): it ignores the week's temperature, heat waves, wind and plant cover. |
| H2 | Baseflow releases 4% of groundwater **per step** (LiveWater.kt:298, HexLand.kt:272) | arbitrary (step-dependent) | The residence time (about 25 weeks) depends on `daysPerStep`, and karst aquifers drain over years. Groundwater stays inside its place and never flows down to lower bands or hexes. |
| H3 | Hex routing down each hex's main river, scaled by a running mean so flow matches the fine river, with the ratio clamped to 0.3-3 (HexLand.kt:276-282) | rough | It is a reasonable calibration. The clamp is arbitrary, and the correction is diagnostic only: it changes floods but conserves no water. |
| H4 | Water reaching a closed basin (`down = -1`, not the sea) disappears; lakes are not places and have no level (HexLand.kt:137, 163-167; HexWorld.kt:117) | arbitrary (open system) | This breaks the closed water budget. Lake level and salinity (the Lisan lake) are a central Late Pleistocene signal and a driver of water and habitat. |
| H5 | Flood: hex flow above bankfull (4 × mean) floods the floodplain share; depth > 1 m cuts herbs by 50%·fs and shrubs by 10%·fs per step, and depth > 3 m cuts trees by 3%·fs per step (HexLand.kt:283-286, 325-327) | rough / step-dependent | Floods kill plants but **never wet the soil and never lay down silt**, even though these are the main ecological effects of floods. |
| H6 | Drinking water: rivers in proportion to flow/mean, fresh lakes always, springs while the weekly mean is above −2 °C, pools if soil wetness ≥ 0.8 or rain ≥ 3 mm/day (HexLand.kt:120-132) | rough | Springs never weaken in drought, and spring water near the annual mean temperature does not freeze. `waterKm` divides by max(0.2, …), an ad-hoc floor. Snow as a water source is missing. |
| H7 | Spring placement: aridity ≤ 0.65, no river ≥ 0.1 m³/s, recharge ≥ 3e4 m³/yr; P = cell/150 km² × 3 × flow × (0.2 + foothill + fault + karst) (HexLand.kt:83-101) | arbitrary in form | The processes are right. But the hard aridity cutoff leaves the karst hills, the classic spring country, without springs once aridity > 0.65. `GROUND_SHARE` 0.3 conflicts with `RECHARGE` 0.4 in the same file. |
| H8 | Fine LiveWater: lakes and reservoirs evaporate 1.1 × PET, negative lake volume is thrown away, irrigation can take at most 80% of flow (LiveWater.kt:93-95, 139, 169) | rough | Open-water evaporation of 1.0-1.3 × PET is right. Discarding negative volume (`max(v,0)`) creates water. This model is not used at runtime. |
| P1 | Ceiling = maxBiomass × moisture ramp × warmth ramp × (0.3 + 0.7·fertility) × waterlogging (HexLand.kt:239-245) | rough | Linear ramps between hand-set thresholds. There is no winter-cold limit, so evergreen and deciduous trees and frost-tender shrubs do not separate. There is no CO₂ effect, although Late Pleistocene CO₂ at about 190-230 ppm cut C3 water-use efficiency by roughly 20-40% (from memory). |
| P2 | One moisture memory for every life form, e-folding over 360 days (HexLand.kt:291, 298) | arbitrary | Annual herbs respond to this season's rain, and trees to several years'. With one memory, a drought year barely moves the herb ceiling. |
| P3 | Warmth tracker: rises halfway to the week's temperature, falls with a memory of about 3600 days (HexLand.kt:299) | rough / biased | It tracks the hottest weeks (anomalies and heat waves), so "warmest month" reads 2-4 °C high and trees climb higher than their thresholds intend. |
| P4 | Growth season = (t − 2)/13, × 0.3 if t > 38 °C, × water0 = wet/0.5 (HexLand.kt:300-301) | rough / arbitrary step | One response for all forms. The 38 °C step almost never triggers on a weekly mean. Heat stress should begin at about 30-35 °C and rise smoothly. |
| P5 | Logistic growth with seed-rain establishment `cap*0.05` (HexLand.kt:345-351) | rough | It is fine as a form. |
| P6 | Dieback above the ceiling at a rate of min(1, 2·time + 0.002), where time = season × water × years (HexLand.kt:349) | **arbitrary / inverted** | Excess biomass dies fastest in good growing weeks and almost not at all in drought (0.002 per step), which is the opposite of stress mortality. |
| P7 | Browsing suppresses young trees and tree seed; grazing produces a lawn (up to −50% herb cap); rooting boosts seed and lowers the herb cap (HexLand.kt:307-315) | grounded in direction, rough in form | Coefficients 0.8/0.9/0.6/0.5/0.2 are hand-set. |
| P8 | Frost kill: × 0.3 per step when the weekly mean is below −45/−30/−25 °C (HexLand.kt:321-323, Vegetation.kt:23-25) | arbitrary / never fires | Weekly means in the Levant never reach these values. The real cold limits are absolute minimum temperature (olive about −8 °C, carob about −4 °C, oak −20 °C or lower) and late spring frost on new growth and flowers, from memory. |
| P9 | Ash smothers herbs (e-folding at 50 mm over about 10 weeks) and shrubs 4× less, and adds 0.002 fertility per mm per year (HexLand.kt:317-320) | rough | Right processes. The > 1 mm threshold is a hard step. |
| P10 | Fertility relaxes back to the bedrock value over 50 years (HexLand.kt:328) | arbitrary (open matter) | Nutrients appear or vanish without a source. |
| P11 | Erosion: fertility × (1 − 0.05·bare²·rain/50·slope/0.2·years), capped at 50% (HexLand.kt:329-331) | rough / open | The soil lost goes nowhere: it is not deposited downslope, on the floodplain or in lower bands. Trails and trampling do not add to erosion. |
| P12 | Litterfall = losses + years × (herb + 0.2 shrub + 0.04 tree) (HexLand.kt:333) | **arbitrary (creates matter)** | The turnover term is added to litter **without being taken off the plants**. The logistic growth is net, so this litter is extra matter feeding the small-life chain. |
| P13 | Trampling: herbs × e^(−0.05·tr), shrubs × e^(−0.1·tr) per step; trails wear and fade (HexLand.kt:335-340) | rough | Grasses tolerate trampling, so the direction is right. The coefficients and the unit of `tr` are unexplained. |
| P14 | Seed sources: tree/5 and shrub/1, saturating; neighbours give half; recomputed every 5 steps (HexLand.kt:292, 421-425) | rough | It gives forest spread of about 0.5 km/yr, which is plausible (Holocene oak spread 0.1-0.5 km/yr, from memory). "Every 5 steps" depends on step length. |
| F1 | Fire allowed only if fuel ≥ 0.2, soil wetness < 0.3, t > 15 °C and rain < 5 mm **per step** (HexLand.kt:355-356) | arbitrary thresholds | Four hard gates. The rain gate depends on step length. |
| F2 | Fuel = (herb + 0.5 shrub) × grazing reduction (HexLand.kt:353) | rough / wrong pool | It uses **live** herb, ignoring standing dead (`HexFood.straw`), litter and curing (`green`). Grazing does reduce fuel, which is good. |
| F3 | Ignitions 0.01/km²/yr × fuel × heat (HexLand.kt:374-376, Vegetation.kt:333) | rough | The order of magnitude is plausible for lightning. Ignitions are not tied to storminess or regime: Levant lightning comes with autumn and winter fronts, after the dry season has cured the fuel. |
| F4 | Spread: breadth-first to neighbours with P = (0.2 + 0.4·min(1, v/8)·cos) × share × fuel, at most **60 places per step** (HexLand.kt:381-396) | arbitrary cap | `Vegetation.SPREAD_KM_PER_DAY = 5` is ignored. 60 places of about 10 km each in one 6-day step is roughly 10× the physically possible run (about 30 km). |
| F5 | Burn share 0.1 + 0.6·fuel/1.5, at most 0.7; burn fractions herbs 0.9, shrubs 0.8, trees 0.3; fertility +0.01·share (HexLand.kt:402-409) | rough | The fire does not burn straw, litter, fruit, seeds or the small-life litter pool. |
| G1 | Greenness relaxes 30% per step towards season × water (+ post-fire flush ×1.5); nourishment = 0.65 + 0.35·green (HexLand.kt:304-305, 219) | rough | 0.65 is an energy-digestibility ratio. Dry-season forage is protein-limited (crude protein about 3-5% against a maintenance need of about 7%, from memory), so the effective value of cured grass is about 0.3-0.5 of green. The 30% per step depends on step length. |
| E1 | Eruption rate per volcano; VEI geometric with ratio 5 (+1 for non-hotspot), capped at 6 (Volcanism.kt:195-205) | grounded | It matches the global frequency-magnitude relation. |
| E2 | Ash: 50 × 2.5^(VEI−2) mm at 1 km, e-folding length 4 × 2.6^(VEI−2) km, three times shorter upwind and narrower across (Volcanism.kt:207-227) | grounded | I integrated it: VEI 4 ≈ 0.16 km³, VEI 5 ≈ 3 km³, VEI 6 ≈ 45 km³, all inside the VEI volume classes. Its direction comes from one weekly low-level wind (HexSim.kt:35-39), whereas high plumes follow the upper westerlies. |
| E3 | Ash decays e^(−days/720) and is zeroed below 0.05 mm (Volcanism.kt:178-181) | rough | Removal should depend on rain, slope and plant cover, and the ash should move with the water (into the rivers and floodplains). |
| D1 | `HexFood` fruit: in months 7-10, equilibrium stock = 0.0008 × bearing × fruitSet, falling over 30 days into litter (HexFood.kt:40-43) | arbitrary timing / low / open | Fixed calendar months for every band and latitude. Fruit is created without being taken off the shrubs and trees. There are no mast years and no effect of drought or frost on fruit set. |
| D2 | Grass seed: months 5-7 (about 20 May to 18 August), 0.01 × herb, shed over 40 days into litter (HexFood.kt:45-48) | arbitrary timing / low / open | Lowland Levant cereals ripen in late April and May (from memory), so the window is a month late there. The same window applies at every elevation. Shed seed becomes litter rather than a seed bank. |
| D3 | Roots: target = 0.0003 × (herb + 0.05 tree) × 3 in months 9-11, otherwise × 0.5, replaced over 180 days (HexFood.kt:49-50) | arbitrary | The "cap" is not a cap. Mediterranean geophytes store most in late spring as their leaves die. The stock is tiny (about 1 kg/ha). |
| D4 | Straw: relaxes over 180 days to 0.0025 × (herb + 0.3 shrub) (HexFood.kt:51) | **arbitrary / wrong flow** | It is not fed by senescence. It is about 100× smaller than real standing dead (1-4 t/ha by late summer, from memory). Eating straw takes **live** herb and shrub (HexFood.kt:66). |
| D5 | `take()` removes eaten fruit and roots from the bearing plants (HexFood.kt:59-69) | rough | Eating a fruit should not cut shrub biomass. The fruit should have been taken off the plant when it was made. |
| D6 | `roughage` = 0.4 × fibreDigestion × (mass/100)^0.25, capped at 0.75 (HexFood.kt:92) | grounded | Jarman-Bell: gut capacity ∝ M¹ and need ∝ M^0.75 give retention ∝ M^0.25. The 0.75 cap is hand-set. |
| D7 | `access`: ruggedness above 0.4 bars non-climbers from up to 80% of the food, reaching that limit at 0.8 (HexFood.kt:98-101) | rough | The thresholds are hand-set but the idea is sound. |
| X1 | Height bands at −1000, 500, 1000, 1500 and 2500 m (HexWorld.kt:195) | arbitrary | The lowest band lumps the Dead Sea floor (−400 m) with 500 m hills, a 6 °C spread and the sharpest ecotone in the region. There is one 1000 m band at the top. |
| X2 | `placeTemp` = hex mean − 6.5 °C/km × (band height − hex height) (HexWorld.kt:130) | rough / inconsistent | The weather uses each band's own mean normals (HexWorld.kt:142), while comfort and reproduction use `placeTemp`, so two temperatures exist for one place. The Climate lapse uses max(elev, 0) (Climate.kt:63), so below-sea ground gets no extra warmth in the normals. |
| X3 | `watered` = within 2 km (HexWorld.kt:65), against `DRINK_KM` = 3 km (HexLand.kt:431) | inconsistent | Two definitions of reach to water. |
| X4 | `HexFood` reads `weather.date.month` after the weather has stepped (the coming step), while `HexLand` uses `lastMonth` (HexFood.kt:37, HexLand.kt:262) | bug (small) | Food phenology is one step ahead of the water and plants. |

---

## 2. Constants

"Step-dep." means the value acts per step, so the behaviour changes if `daysPerStep` changes.

| Constant / literal | file:line | Value | Meaning | Verdict | Defensible value / form and basis |
|---|---|---|---|---|---|
| coarseKm | Weather.kt:90 | 50 km | anomaly grid | rough | Fine for rain. Temperature needs a correlation length of about 500-1000 km (synoptic). Use a separate, smoother temperature field. |
| phi = exp(−days/5) | Weather.kt:187 | τ 5 d | weather persistence | grounded | Synoptic decorrelation is 3-7 days. |
| TEMP_SIGMA | Weather.kt:383 | 1.5 °C | weekly temperature noise | rough | Should be seasonal: about 2-3 °C in winter, 1-1.5 °C in summer for weekly means in the eastern Mediterranean (from memory). |
| RAIN_SIGMA | Weather.kt:384 | 0.35 | weekly log-rain noise | rough | Too small for weekly rain. Use an occurrence (wet/dry week) probability plus gamma amounts; dry weeks are common. |
| RAIN_BIAS | Weather.kt:386 | uses 0.3 | lognormal mean correction | bug | Use 0.25 to match line 283, and add the oscillation, decadal and shock variances. |
| REGIME_PERSISTENCE | Weather.kt:388 | 0.5 | chance a regime persists | grounded | Gives a mean of about 12 days. |
| EDDY_SIGMA, τ 7 d | Weather.kt:390, 223 | 2.5 m/s | wind eddies | rough | OK. |
| meander τ 10 d, σ 2° | Weather.kt:220-221 | — | jet meander | rough | OK. |
| volcanic decay τ 360 d | Weather.kt:190 | — | aerosol e-folding | grounded | Stratospheric sulphate e-folds in about 12 months. |
| VEI cooling 0.4 / 1.0 | Weather.kt:167 | °C | cooling by VEI | rough | Tie it to an eruption sulphur yield drawn per eruption: about 0.1-0.3 °C for VEI 5, 0.3-0.6 °C for VEI 6 (from memory). |
| event length 720 d, radius 5000 | Weather.kt:169 | — | record only | unknown | The radius is unused. |
| melt 4 mm/°C/d | Weather.kt:273 | — | degree-day factor | grounded | 2-6 mm. |
| snow if t < 0 | Weather.kt:269 | 0 °C | rain/snow phase | rough | Ramp the snow share from 1 at −2 °C to 0 at +3 °C for weekly means. |
| yearAnomaly φ 0.4, σ 0.25 | Weather.kt:283 | — | year-to-year rain | rough | Scale σ with aridity, roughly CV ≈ 0.25·(P/500)^−0.5, giving about 0.5 at 125 mm (from memory: CV-aridity relations). |
| AR(2) r 0.8, period 5, noise 0.75 | Weather.kt:285-286 | — | oscillation | unknown | The eastern Mediterranean's link to ENSO is weak. Its amplitude belongs with the NAO/EA modes. |
| decadal 0.9 | Weather.kt:288 | — | decadal persistence | rough | OK. |
| 0.015, 2-8 yr, 200-500 km, ×0.55-0.75 | Weather.kt:292-295 | — | multi-year droughts | rough | Give the rate per 10⁶ km² so it is independent of map size. |
| 0.12, 300-700 km, ×0.5-0.7 | Weather.kt:297-299 | — | drought years | arbitrary | Scale the rate so the local frequency is the intended one in 8, which needs a rate of about 0.4/yr/map at this map size. Better: drop it and let a wider `yearAnomaly` with aridity-scaled σ make drought years. |
| cold winter 0.06, +270..340 d, 15-45 d, −5..−9 °C | Weather.kt:301-304 | — | severe winter | **bug** | Start inside midwinter: day + (330..400) mod year, i.e. around days 0-60 of the year. |
| heat wave 0.08, +100..160 d, 12-32 d, +4..+8 °C | Weather.kt:306-309 | — | heat wave | **bug** | Start at day + 170..230. |
| oscillation T 0.7, P 0.15; decadal T 0.4, P 0.12 | Weather.kt:319-320 | — | slow modes | unknown | Calibrate to observed interannual sd: temperature about 0.6-0.8 °C, log-rain about 0.25. |
| drought +0.8 °C, heat wave −0.7 log-rain | Weather.kt:325-326 | — | coupling | rough | Better: derive the warming from the soil-moisture deficit. |
| smoothPattern 6 passes | Weather.kt:337 | — | about 300 km pattern | rough | OK. |
| storminess 1.5/0.7/1.3/0.35/0.4 | Wind.kt:22-30 | — | rain by regime | rough | Should come from a regime rain climatology. OK as relative values. |
| regime weights | Wind.kt:53-57 | — | seasonal frequencies | rough | OK. |
| jet 42 + 6·season | Wind.kt:63 | °N | jet latitude | rough | OK. |
| baseWind numbers (9, 1.5/7, 2/−6, −4/1, …), nearJet floor 0.3, width 8°, south ramp 6° | Wind.kt:70-77 | m/s | regime winds | rough | OK. |
| warmth numbers | Wind.kt:89-93 | °C | regime temperature | rough | OK; it is mean-removed. |
| calm 0.5 m/s; full effect at 5 m/s | Wind.kt:108, 114 | — | orographic onset | rough | OK. |
| OROGRAPHIC 0.01 | Wind.kt:136 | /(m/km) | upslope enhancement | rough | Windward/lee ratios of 2-4 on Levant ridges (from memory) are reproducible with this. |
| SHADOW 0.0008 | Wind.kt:138 | /m | rain shadow | rough | OK. |
| rise cap 150 m/km; clamp 0.2-4; blur 3 km; step 4 km; shadow 6/12/25/50 km; weight 1 − km/100 | Wind.kt:142-161 | — | terrain sampling | arbitrary | The clamp hides extreme shadows (the Dead Sea gets about 0.05-0.1 of the windward rain). Widen it to 0.05-5. |
| SOIL_CAPACITY | LiveWater.kt:294 | 150 mm | plant-available water | arbitrary (uniform) | Derive per place from rock and slope: thin soils on limestone or rock 40-80 mm, loess or alluvium 150-250 mm, sand 50 mm (from memory: AWC × rooting depth). |
| RECHARGE | LiveWater.kt:296 | 0.4 | excess to groundwater | rough | Per rock type: karst 0.6-0.8, basalt 0.5, clay 0.1-0.2. |
| BASEFLOW | LiveWater.kt:298 | 0.04 /step | groundwater release | arbitrary, step-dep. | Write it as 1 − exp(−days/τ), with τ about 100 days for alluvium and 2-10 years for karst. |
| 1.1 × PET for lakes | LiveWater.kt:94, 149 | — | open-water evaporation | grounded | 1.0-1.3. |
| 0.8 of flow | LiveWater.kt:169 | — | irrigation limit | arbitrary | A human rule; not ecology. |
| soil start 0.6 × capacity | HexLand.kt:190 | — | initial state | fine | Start state only. |
| moisture start aridity/1.5 | HexLand.kt:198 | — | initial state | rough | Different scale from the dynamic mean soil wetness, which causes a start-up transient. |
| FLOW_MEMORY | HexLand.kt:447 | 0.01 /step | flow correction | step-dep. | Use 1 − exp(−days/730). |
| clamp 0.3-3, rawMean > 0.01 | HexLand.kt:279 | — | flow correction | arbitrary | Widen it or remove the clamp. |
| bankfull 4 × mean; stage 1.5 × Δdepth | Rivers.kt:30, 36 | — | flood stage | rough | Bankfull is 1.5-2-year return flow. For flashy arid wadis the ratio of bankfull to mean is 10-100×. Scale it with aridity or with the flow's CV. |
| flood > 1 m: herbs −50%·fs, shrubs −10%·fs; > 3 m trees −3%·fs | HexLand.kt:326-327 | per step | flood kill | arbitrary, step-dep. | Make it depend on submergence duration and season (dormant plants tolerate weeks); a per-day rate. |
| DRINK_KM | HexLand.kt:431 | 3 km | easy walk to water | rough | 2 km is used in HexWorld.kt:65. Unify. |
| MAX_WATER_KM | HexLand.kt:432 | 60 km | distance cap | fine | Bookkeeping. |
| POOL_WETNESS | HexLand.kt:433 | 0.8 | pools everywhere | rough | Pools come from runoff and rock basins. Tie them to Hortonian runoff once it exists. |
| pools if rain ≥ 3 mm/day | HexLand.kt:126 | — | wet-week pools | rough | OK as a proxy. |
| thawed > −2 °C | HexLand.kt:124 | — | frozen water | arbitrary | Springs do not freeze. Pools freeze at a weekly mean below 0 °C, streams below about −5 °C. |
| waterKm floor 0.2 | HexLand.kt:130 | — | distance stretch | arbitrary | Use the actual distance to the nearest water still running. |
| GROUND_SHARE | HexLand.kt:435 | 0.3 | spring recharge share | inconsistent | Use `RECHARGE`. |
| MIN_SPRING_FLOW | HexLand.kt:436 | 3e4 m³/yr | smallest spring | rough | About 1 L/s, a reasonable perennial spring. |
| SPRING_KM2 and the extra ×3 | HexLand.kt:437, 97 | 150 km² | spring density | arbitrary | Drop the ×3. |
| aridity > 0.65 → no springs | HexLand.kt:89 | — | — | arbitrary | Remove it: springs are where groundwater meets the surface, in any climate. |
| discharge ≥ 0.1 → no springs | HexLand.kt:89 | — | — | arbitrary | Remove it. |
| foothill /200 m, reach 4 km, fault < 10 km +0.5, karst +0.5, base 0.2 | HexLand.kt:84, 93-97 | — | spring odds | rough | OK as weights. |
| oasis: aridity < 0.3 and 10 × min flow | HexLand.kt:99 | — | oasis | rough | OK. |
| moisture memory 360 d | HexLand.kt:291 | — | ceiling memory | arbitrary | Make it per life form: herbs about 90 d, shrubs about 2 yr, trees about 5 yr. |
| warmth rise 0.5, fall 0.1 × memory | HexLand.kt:299 | — | warmest-month tracker | rough/biased | Use a running mean of the hottest 30 days or a 1-year growing-degree-day sum. |
| season (t − 2)/13 | HexLand.kt:300 | °C | growth vs temperature | rough | Per form: C3 grasses grow from about 0 °C, optimum 15-20 °C. |
| t > 38 → × 0.3 | HexLand.kt:300 | — | heat stress | arbitrary | Smooth decline from about 32 °C to 0 at about 45 °C (leaf temperature), from memory. |
| water0 = wet/0.5 | HexLand.kt:301 | — | water limit | rough | OK. Growth is commonly unlimited above about 50% of available water. |
| GREEN_RATE | HexLand.kt:440 | 0.3 /step | greenness lag | step-dep. | Use 1 − exp(−days/18). |
| FLUSH | HexLand.kt:442 | 1.5 | post-fire green flush | rough | OK. |
| DRY_VALUE | HexLand.kt:439 | 0.65 | nourishment of cured forage | rough (high) | 0.4-0.5 once protein limits are included. |
| browse/young-tree coefficients 0.8, 0.9, 0.5, 0.8, 0.6; shade 0.6/0.8; lawn 0.5; rooted 0.2, 1.5 | HexLand.kt:308-315 | — | disturbance effects | rough | Directions are grounded; magnitudes unknown. |
| young = tree < 0.3 cap | HexLand.kt:308 | — | sapling stand | rough | OK. |
| BROWSE_SCALE 3, GRAZE_SCALE 5, LAWN_SCALE 8, ROOT_SCALE 2 | Vegetation.kt:325-331 | kg/km²/d | disturbance scales | unknown | 3 kg/km²/d is about one deer per 3 km². Plausible scales, but they should scale with plant productivity: the same offtake matters far more in desert than in forest. Express them as a fraction of NPP. |
| maxBiomass 0.6/3/25; growth 6/0.8/0.25 per yr | Vegetation.kt:23-25 | kg/m², /yr | life forms | rough | Plausible. Tree 25 kg/m² (250 t/ha) is mature-forest level. |
| moisture 0.05-0.35 / 0.12-0.45 / 0.3-0.7; warmth 4-12 / 6-14 / 10-16 | Vegetation.kt:23-25 | — | niche ramps | rough | Add a winter-minimum limit per form. |
| frostKill −45/−30/−25 °C on the weekly mean, × 0.3 per step | Vegetation.kt:23-25; HexLand.kt:321-323 | — | frost mortality | arbitrary, never fires, step-dep. | Use the expected weekly minimum (mean − about 8 °C) against a per-form hardiness, and add damage from frost during growth (green > 0.5). |
| ASH_SMOTHER_MM 50, shrubs ×4, factor 52/10 | HexLand.kt:444, 318 | — | ash burial | rough | OK; the literature has herb loss at 5-15 cm. |
| ash > 1 mm gate | HexLand.kt:317 | — | — | arbitrary | Remove; the exponential already makes thin ash harmless. |
| ASH_FERTILITY 0.002 /mm/yr | HexLand.kt:445 | — | weathering | rough | OK, but it should come from the ash itself (the ash is consumed as it weathers). |
| SOIL_RECOVERY_YEARS 50 | Vegetation.kt:340 | — | fertility return | arbitrary (open) | Replace with weathering input + deposition − erosion. |
| EROSION 0.05, rain/50, slope/0.2, max 0.5, bare = (1 − cover)² | Vegetation.kt:345; HexLand.kt:329-331 | — | erosion | rough | RUSLE-like; the cap and the squared cover are hand-set. The eroded material must be deposited downslope. |
| cover = herb/0.3 + shrub + canopy | HexLand.kt:329 | — | ground cover | rough | OK. |
| canopy = tree/10 | HexLand.kt:235 | — | closed canopy at 100 t/ha | rough | OK. |
| litter turnover 1, 0.2, 0.04 /yr | HexLand.kt:333 | — | turnover | grounded rates / **open matter** | Subtract them from the plants and raise gross growth to match. |
| trampling 0.05, 0.1 | HexLand.kt:337 | per unit/step | trampling loss | unknown, step-dep. | — |
| TRAIL_WEAR 0.3, TRAIL_FADE 1/yr | Vegetation.kt:337-338 | — | trails | rough | — |
| burnt fade 0.4/yr | HexLand.kt:341 | — | post-fire state | rough | OK (a 2-3 year flush). |
| seed tree/5, shrub/1, neighbour 0.5, every 5 steps | HexLand.kt:422-424, 292 | — | seed sources | rough, step-dep. | Run it every step or by days. |
| establishment 0.05 × cap | HexLand.kt:348 | — | seedling input | rough | — |
| dieback min(1, 2·time + 0.002) | HexLand.kt:349 | — | over-cap loss | **arbitrary/inverted** | Rate ∝ (1 − water0)·(v − cap)/τ_form, with τ about 0.25 yr for herbs, 1 yr for shrubs and 3 yr for trees. |
| fire: fuel ≥ 0.2, wetness < 0.3, t > 15, rain < 5 mm/step | HexLand.kt:356 | — | burn gates | arbitrary | Use a continuous fuel-moisture index from wetness, green and rain, and drop the gates. |
| heat (t − 15)/20 | HexLand.kt:374 | — | fire weather | rough | — |
| IGNITIONS 0.01/km²/yr | Vegetation.kt:333 | — | lightning ignitions | rough | Scale with regime storminess × dry fuel. |
| fuel/1.5 saturation | HexLand.kt:375, 402 | kg/m² | — | rough | — |
| spread 0.2 + 0.4·min(1, v/8)·cos | HexLand.kt:392 | — | wind spread | rough | — |
| MAX_SPREAD 60 places | HexLand.kt:429 | — | spread cap | **arbitrary** | Use breadth-first depth ≤ SPREAD_KM_PER_DAY × days / hexKm (about 3 hexes at 5 km/day over 6 days), with wind boosting the downwind distance. |
| burnShare 0.1 + 0.6, ≤ 0.7 | HexLand.kt:402 | — | area burnt | rough | — |
| burn herbs 0.9, shrubs 0.8, trees 0.3; fertility +0.01 | HexLand.kt:406-408 | — | fire effects | rough | Fertility should come from the burnt biomass's nutrients (ash), not a constant. |
| FRUIT_SHARE 0.0008; bearing 0.5 shrub + 0.05 tree | HexFood.kt:73, 32 | — | fruit stock | arbitrary (low) | Annual fruit/mast is about 2-10% of shrub annual production; acorns 100-1000 kg/ha in oak woodland, varying 10× between years (from memory). The stock is about 5-10× too small and has no masting. |
| FRUIT_DAYS 30 | HexFood.kt:74 | d | fruit lifetime | rough | OK. |
| fruit months 7-10 | HexFood.kt:40 | — | ripening window | arbitrary | Trigger on accumulated growing degree-days after flowering, which shifts with band and year. |
| SEED_SHARE 0.01 | HexFood.kt:76 | — | grass seed | arbitrary (low) | Annual grassland seed yield is about 5-15% of above-ground production; wild cereal stands reach 0.5-1 t/ha (Harlan, from memory). |
| SEED_DAYS 40 | HexFood.kt:77 | d | seed on the stalk | rough | 2-4 weeks for shattering wild cereals. |
| seed months 5-7 | HexFood.kt:45 | — | ripening window | arbitrary | Ripen when green falls after the growth flush (curing); a month earlier in the lowlands. |
| ROOTS_SHARE 0.0003; tree 0.05 | HexFood.kt:79, 33 | — | geophytes and mast | arbitrary (tiny) | Geophyte storage tens to hundreds of kg/ha. |
| ROOTS_DAYS 180; ×3 in months 9-11, else ×0.5 | HexFood.kt:80, 49 | — | roots seasonality | arbitrary | Build up during green growth, peak as the leaves die (late spring). |
| STRAW_SHARE 0.0025; shrub 0.3 | HexFood.kt:82, 34 | — | standing dead | **arbitrary (×100 too low)** | Make it a pool fed by senescence of live herb. |
| STRAW_DAYS 180 | HexFood.kt:83 | d | straw build-up | rough | Weathering/decay of standing dead: about 0.5-1.5/yr. |
| straw take 0.7 herb / 0.3 shrub | HexFood.kt:66 | — | — | **wrong** | Take from the straw only. |
| ROUGHAGE_VALUE 0.4, cap 0.75, exponent 0.25 | HexFood.kt:85, 92 | — | Jarman-Bell | grounded (exp.) / rough | — |
| access 0.4 / 0.8 / 0.8 | HexFood.kt:100 | — | rugged-ground access | rough | — |
| BANDS_M −1000/500/1000/1500/2500 | HexWorld.kt:195 | m | height bands | arbitrary | Split at 0 m (or −200 m) and keep steps of about 500 m to the top, or use quantiles of each hex's relief. |
| lapse 6.5e-3 | HexWorld.kt:130 | °C/m | lapse rate | grounded | — |
| watered 2 km | HexWorld.kt:65 | — | — | inconsistent | Unify with `DRINK_KM`. |
| tenth (low/high ground) | HexWorld.kt:93 | — | valley/peak | rough | — |
| VEI ratio 5, +1 for arc/rift, cap 6 | Volcanism.kt:201 | — | VEI distribution | grounded | — |
| ash 50 × 2.5^k mm, 4 × 2.6^k km, upwind ×3, across ×2, reach 5L, > 0.1 mm | Volcanism.kt:210-225 | — | ash fall | grounded | Volumes check out. |
| ash residence 720 d, < 0.05 mm → 0 | Volcanism.kt:178-181 | — | ash removal | rough | Tie it to rain and slope. |
| activity: age < 0.3 Myr, 80% active, scale × (0.5-4.5)/century | Volcanism.kt:111-112 | — | eruption rates | rough | — |

---

## 3. Interactions

### What feeds what now

- **Weather → water.** Temperature splits rain from snow. The degree-day rule turns snowpack into meltwater. Rain and melt fill the soil bucket; ET uses the *climatological* PET; the excess goes to groundwater and the river. Hex flow feeds floods and drinking water (`running`). Soil wetness above 0.8 or heavy rain makes pools.
- **Weather → plants.** Temperature drives the growth season, the warmth tracker and frost kill. Soil wetness drives water0 and the 1-year moisture memory, which sets the ceilings. Season × water drives greenness, which sets forage value and the post-fire flush.
- **Wind.** It sets orographic rain, fire spread direction, the ash plume direction and (through regimes) the week's warmth.
- **Eruptions.** Ash smothers herbs and shrubs, adds fertility and harms fauna. VEI 5 and up cool the whole map.
- **Animals → plants.** Grazing gives lawns and less fuel. Browsing suppresses saplings. Rooting turns over seedbeds and suppresses herbs. Trampling and trails reduce plants. Animal-carried seed spreads shrubs and trees. Consumption is taken off the biomass. Dung and carcasses go to small-life litter, which becomes organic fertility and raises the ceiling.
- **Fire.** It removes biomass, sets `burnt`, which makes a green flush, and adds a little fertility. Bare cover leads to erosion and fertility loss.
- **Plants → food stocks.** Shrubs and trees bear fruit, scaled by pollination. Herbs produce seeds, roots and straw. Eating these takes plant biomass. Fallen fruit and shed seed become litter.

### Missing interactions (real ecology has them)

1. **Weekly temperature, wind and plant cover → evaporation.** PET is the monthly normal (HexLand.kt:43, 268). A heat wave, a drought's +0.8 °C, a northerly wind or a volcanic winter changes nothing in soil drying. Drought is therefore only a lack of rain, never added demand, and evapotranspiration is the same for bare grazed ground and forest. *This matters because* the most dangerous combination, heat plus drought, is the one that kills plants and stock.
2. **Plant cover and trampling → infiltration and runoff.** There is no Hortonian runoff, and bare or trampled ground sheds no extra water. Grazing therefore cannot cause the desertification loop (less cover → more runoff and erosion → drier soil → less cover). Arid wadis never flash-flood, and desert pools after rain are missing.
3. **Fire ↔ dead fuel.** Fuel is live herb (HexLand.kt:353). The straw and litter pools that actually burn are neither read nor burnt, and curing (`green`) does not dry the fuel. Grazers eating straw do not reduce fire risk through the straw.
4. **Drought, heat and frost → fruit and seed set.** `fruitSet` depends only on pollination. Real mast failure follows spring drought or late frost, and mast years synchronise boar, rodents and birds. Seed set does not depend on the spring's water either.
5. **Weather → phenology.** Ripening is fixed to calendar months (HexFood.kt:40, 45, 49), the same at −400 m and 2500 m. Altitudinal migration to follow ripening, which is the whole reason for having bands, cannot emerge from fruit and seed timing.
6. **Senescence → standing dead.** Herbs that dry in summer stay live biomass at 65% value. Straw is a separate, tiny pool that is not fed by senescence. The dry-season bottleneck (protein) is too mild.
7. **Floods → soil water and silt.** Floods only kill plants. Floodplain recession pastures and fertile alluvium, the richest lowland habitat, do not emerge.
8. **Erosion → deposition.** Soil lost on slopes vanishes (HexLand.kt:331). It should build lower-band and floodplain fertility. This is a closed-system violation.
9. **Groundwater → springs and lower bands.** Springs are fixed and run forever; groundwater never flows from upland bands to lowland places. In a multi-year drought springs should shrink with a lag, and that lag is exactly what makes them refuges.
10. **Closed basins → lake level.** Inflow to closed basins vanishes. A terminal lake whose size follows the climate (Lisan lake) would feed back on drinking water, wetlands and salt.
11. **CO₂ → water-use efficiency.** Late Pleistocene CO₂ of about 200 ppm should cut C3 plant production per mm of water. Today's productivity is assumed.
12. **Winter cold → which plants grow.** No minimum-temperature limit, so the ranges of frost-tender Mediterranean evergreens (olive, carob, wild pistachio) against cold-steppe shrubs cannot form.
13. **Ash → water and drinking.** Ash should move into rivers and floodplains with the rain. It disappears in place over 2 years whatever the rain or slope.
14. **Lightning → fire timing.** Ignitions should follow storms (autumn fronts on cured fuel), not just heat.
15. **Production → food stocks as a flow.** Fruit, seed and straw are made without taking the matter from the plants (HexFood.kt:40-51). Eating straw kills live herbs (HexFood.kt:66). This breaks mass balance in both directions.
16. **Herbivory scales against productivity.** The disturbance scales are absolute (kg/km²/d), so 3 kg/km²/d of browsing does the same in desert and forest. They should be relative to plant growth there.

---

## 4. Top 10 problems (ranked by how much they distort the ecology)

1. **The plant–food–litter loop does not conserve matter.**
   - Where: turnover litter is added without being taken off the plants (HexLand.kt:333). Fruit, seed and straw are created from nothing and fallen fruit and seed become litter (HexFood.kt:40-51). Eating straw removes *live* herb and shrub (HexFood.kt:66). Fertility relaxes to bedrock and eroded soil vanishes (HexLand.kt:328, 331).
   - Why it matters: the small-life and detritivore chain is overfed. Grazers eating "straw" in the dry season destroy the live stand. Nutrients are not closed.
   - Fix (reshape): treat logistic growth as gross. Each step, move turnover from live to litter, move fruit and seed production from the bearing pool to fruit/seed, and move senescence from herb to straw. `take(straw)` removes from straw only. Eroded fertility is deposited on the next band down or on the floodplain of the receiving hex.

2. **Cold snaps and heat waves fire in the wrong season.**
   - Where: `newYear()` runs at the solstice from year 1 onwards, so `day + 270..340` puts the severe winter in late September to November and `day + 100..160` puts heat waves in April to early June (Weather.kt:303, 308).
   - Fix (parameters): cold start = day + 330 + rand(70) (that is, from day −30 to +40 around midwinter, wrapping into the next year); heat start = day + 170 + rand(60).
   - Related: the seasonal cycle lags about 15 days (Weather.kt:195). Interpolate from mid-month: f = (dayOfMonth − 15.5)/30, using the previous or next month accordingly.

3. **Evaporation ignores the weather.**
   - Where: soil ET uses the monthly PET normal (HexLand.kt:43, 268).
   - Why it matters: heat waves, drought warming, volcanic winters and northerly regimes have no effect on drying, so droughts are milder than they should be and heat has no water cost.
   - Fix (reshape): recompute Thornthwaite for the week's temperature with the existing `Climate.thornthwaite`, or use pet × exp(0.06 × (T − T_normal)), roughly the Clausius-Clapeyron slope. Optionally scale by plant cover: bare soil ET ≈ 0.3-0.5 × PET once the top 20-30 mm are dry.

4. **Rain is too smooth and the arid zone too reliable.**
   - Where: no dry weeks (Weather.kt:206), interannual CV of about 0.3 everywhere (Weather.kt:283), drought-year rate per map with a wrong "1 in 8" (Weather.kt:297), and the `RAIN_BIAS` mismatch (Weather.kt:386).
   - Why it matters: deserts never fail, so desert species face no bust years and fire weather and pools are mis-timed.
   - Fix (parameters): make the year σ depend on aridity (about 0.2 humid to 0.6 hyper-arid), give event rates per 10⁶ km², and correct `RAIN_BIAS` (0.25 plus the slow-mode variances).
   - Fix needing a small NEW mechanism: weekly rain occurrence, where P(wet step) comes from storminess and the monthly wet-day count and amounts are gamma-distributed.

5. **The dry-season bottleneck is wrong.**
   - Where: herbs never senesce. They stay as live biomass worth 0.65 of green (HexLand.kt:219, 439). Straw is tiny (×0.0025) and not fed by drying (HexFood.kt:82).
   - Why it matters: summer and winter carrying capacity, which the file comment itself names as what sets grazer numbers, is overstated.
   - Fix (reshape): when green falls, move herb biomass to straw at a rate ∝ (1 − flush) × herb (about 0.05-0.1 per day in drought). Straw weathers at about 1/yr and burns. Nourishment then becomes green for live herb and `DRY_VALUE` about 0.45 for straw, which makes `STRAW_SHARE` obsolete.

6. **Fire is governed by caps and gates, not fuel physics.**
   - Where: `MAX_SPREAD = 60` places per step (HexLand.kt:429), about 10× a realistic 6-day run, while the existing `SPREAD_KM_PER_DAY` is ignored. Four hard gates (HexLand.kt:356). Fuel is live herb (HexLand.kt:353). Ignitions are not tied to storms.
   - Fix (reshape):
     - limit the breadth-first depth to SPREAD_KM_PER_DAY × days / hexKm, longer downwind;
     - fuel = straw + litter + (1 − green) × herb + 0.5 shrub, with the grazing reduction applying through straw that has actually been eaten;
     - replace the gates with a continuous dryness index;
     - ignition ∝ storminess × dry fuel;
     - burning consumes straw and litter.

7. **Fixed-calendar phenology and no weather on fruit and seed.**
   - Where: HexFood.kt:40, 45, 49.
   - Why it matters: bands at −400 m and 2500 m ripen in the same weeks, so altitudinal tracking of food cannot emerge. Lowland grass seed is a month late, and drought or late frost never causes a fruit failure.
   - Fix (reshape): seeds ripen while green is falling after a flush; fruit ripens after N growing degree-days since the spring flush; roots peak as green falls. Fruit set = pollination × min(1, spring soil wetness / 0.5) × (1 − frost during flowering).
   - Fix needing a NEW mechanism: masting as a lagged response to the previous year's weather plus resource depletion. Raise `FRUIT_SHARE`, `SEED_SHARE` and `ROOTS_SHARE` about 5-10× once they are flows taken out of the plants (see #1).

8. **Soil water is uniform and only saturation-excess.**
   - Where: `SOIL_CAPACITY` = 150 mm everywhere, `RECHARGE` = 0.4 everywhere, `BASEFLOW` per step (LiveWater.kt:294-298, HexLand.kt:268-274).
   - Why it matters: rocky karst and deep alluvium hold the same water, so habitat contrast within the Levant is lost and arid runoff never happens.
   - Fix (parameters): per place from rock and slope, capacity 40-250 mm, recharge 0.1-0.8 (karst high), baseflow 1 − exp(−days/τ) with τ from 100 days to years.
   - Fix needing a NEW mechanism (small): infiltration-excess runoff when weekly rain intensity exceeds an infiltration rate lowered by bare cover and trails.

9. **Plant dynamics invert stress mortality and use one memory.**
   - Where: dieback above the ceiling is fastest in good weeks (HexLand.kt:349). Herbs, shrubs and trees share one 1-year moisture memory (HexLand.kt:291). The 38 °C step and the −25/−30/−45 °C frost limits never act (HexLand.kt:300, 321-323). The warmth tracker reads high (HexLand.kt:299).
   - Fix (reshape):
     - dieback rate ∝ (1 − water0) × (v − cap) / τ_form;
     - per-form memory (90 d, 2 yr, 5 yr);
     - heat a smooth ramp from 32 °C;
     - frost on the expected weekly minimum against per-form hardiness, plus a growth-season frost penalty;
     - warmth = a running mean of the hottest 30 days.

10. **The water system is not closed and springs are static.**
    - Where: closed-basin inflow vanishes and lakes have no level (HexLand.kt:137-167). Springs ignore groundwater and drought and are barred where aridity > 0.65 (HexLand.kt:89, 128). Floods never wet soil or lay silt (HexLand.kt:283-286, 325-327). Ash removal ignores rain (Volcanism.kt:178).
    - Fix (reshape): spring water = nearSpring × min(1, groundwater of the hex's upper bands / their mean); drop the aridity and discharge gates; flood depth adds water to the floodplain share's soil and deposits the upstream eroded fertility.
    - Fix needing a NEW mechanism: a terminal-lake store per closed basin, whose area = volume / depth from the hypsometry and whose evaporation is 1.1 × PET, setting the lake share and drinking water.

**Also worth fixing (smaller):**
- The lowest height band lumps −400 m to 500 m (HexWorld.kt:195): split it at 0 m.
- There are two temperatures per place (`placeTemp` against the weather normals) and the Climate normals give no warming below sea level (HexWorld.kt:130, Climate.kt:63).
- `HexFood` reads the month one step ahead (HexFood.kt:37).
- `GROUND_SHARE` (0.3) contradicts `RECHARGE` (0.4).
- Volcanic cooling should come from a sulphur draw, not VEI (Weather.kt:167).
- Herbivory scales should be relative to NPP.
- Many per-step rates should be written per day: `BASEFLOW`, `GREEN_RATE`, `FLOW_MEMORY`, flood and frost kills, trampling, seed refresh every 5 steps, and the rain < 5 mm/step fire gate.
- There is no CO₂ factor on water-use efficiency, which needs a NEW global parameter of about 0.7-0.8 on the water-limited growth term.
