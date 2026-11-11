# Vacating a hex: defeat, sudden danger, ruin

Status: 2026-11-11. Section 3 (defeat) built in `scatterBeaten` (PeopleSpread.kt); the rest is proposal. Census vacate710
(main 48b83a5f, m5-1..3, 10 y): 0.1-0.3 bands driven from home per 100 bands a year; fire, flood, snow each under 1 death
per 1000 people a year (exposure up to 7.2, hunger up to 5.4). Asked by Olli in the survival thread: bands no longer move
(t.bandsStay, [band-movement-removal.md](band-movement-removal.md)), but sometimes a band must leave its hex quickly. How,
in the current model? And what does losing a fight actually lead to, setting the hex size aside?

Marked throughout: **known** (read in the code at main 4c7445ed), **inferred** (from the code, not run), **from memory**
(ethnography I recall but have not checked against sources: to verify before numbers are set).

## 1. What main does now

- **Known.** Every band takes its step through `stayPlan`, or through `plan` with a reach of 0
  (`GroupPlanner.kt:132`, `if (gs.staysHome(g)) 0f else reach`). Either way it weighs only ground in its own hex.
  The way out of #684/#691 (`searchWayOut`) is still called when the camp is in peril, but the band can take none of
  what it finds. **Inferred:** flight from a foreseen peril is dead code for bands.
- **Known.** The one way a whole band changes hex is `spreadYear` (`PeopleSpread.kt:206`): once a year, only a band that
  wants for food and lives tied to no spot, only when a hex beside is 3x roomier (1.5x after 60 lean days). It jumps
  there in one step (`g.place = hw.firstPlaceIn(best)`).
- **Known.** A band beaten at home in a raid gets `driveOff` (`PeopleRaids.kt:271`): it loses its houses and palisade,
  `drivenFrom`/`drivenBy` are set. Its place is not changed. `keptFrom` (`Band.kt:390`, grudge above `FEUD` 0.9) is read
  only in `planInner` (`GroupPlanner.kt:182`). **Inferred:** a driven-off band stays in the hex it was driven from,
  houseless, next to the band that beat it.
- **Known.** Fire, flood and snow kill people (`GroupHazards.kt:146`, `fauna.calamities` for people too). No band
  foresees them or reacts to them. **Unknown:** how many people they kill a year now; to count (section 6).
- **Known.** Storms (`t.hurricane`) and recruiters are off in recent runs. Peril, PlanWayOut, HexHurricane, Exploring
  are slated for deletion in band-movement-removal.md Phase 7.
- Herds, for comparison (**known**): resident game is numbers per place; each week they drift toward places of higher
  `appeal` (`ResidentMoving.kt:205`), which counts fear (recent kills per km², up to x0.5, `FEAR_WEIGHT`) and the share
  that would live through the week's fire and plague. A gradual flow, not a flight. A band is one unit, so a flow does not
  carry over directly.

## 2. Three kinds of leaving, as observed

The reactions differ in warning, in how far and how long people go, and above all in whether the response is social.

| | Warning | Distance | Duration | Social response |
|---|---|---|---|---|
| Flash flood, grass fire | minutes to hours | a few km: high ground, the burnt side, water | days | none; back to the same spot |
| Eruption, lasting drought, ground gone bad | weeks to years | far, region left | years or for good | bands split, join kin elsewhere |
| Defeat in a fight | none (dawn raid) or the battle itself | away from the enemy, tens of km | as long as the feud lasts; often for good | scatter, refuge with kin/allies, buffer left empty |

Defeat, from memory:
- **Scatter, then regroup.** A camp raided at dawn mostly ends with survivors running into cover, reassembling days later
  (Keeley 1996, *War Before Civilization*).
- **Refuge with kin or allies, not in empty land.** Beaten Yanomamö villages lived with an allied village for months or
  more, then cleared new gardens farther from the enemy (Chagnon). Beaten Mae Enga clans dispersed among in-laws and kin
  in other clans; some later came back (Meggitt 1977, *Blood Is Their Argument*).
- **Away from the enemy, not toward the best ground.** A no-man's-land grows between hostile groups; the Chippewa-Sioux
  buffer was rich in game because neither side dared hunt it (Hickerson 1965).
- **Foragers rarely lose land by conquest.** Their wars kill but seldom move territory (Kelly 2000, *Warless Societies
  and the Origin of War*). Taking and settling the beaten side's land is mostly a farmers' and herders' pattern (Nuer on
  Dinka land, Enga).
- **Return is gated by the enemy, not by time.** While the feud lasts the ground is shunned.

Not every defeat moves anyone. A raid that kills a few and takes stores leaves most groups where they were, behind a
palisade if they have one. Leaving follows a loss that makes staying deadlier than going.

## 3. Proposal: defeat

When a band is beaten at home (`driveOff`), or loses more than a share of its grown people in one raid:

1. **Households go to kin.** A share of its households (built: a fixed `DEFEAT_SCATTER` 0.3, an estimate) leave as parties (`splitOff`) for the nearest kin band within
   `VISIT_RINGS` that is not the raiders' ally; a party too small to live alone joins them (`mergeAndPair`, as
   `spreadYear` already does). The share grows with the loss. The refugees count in their hosts' hex for the
   one-band-per-hex rule as part of the host band, so the rule holds.
2. **The rest moves away.** What is left of the band, if it can still live alone (`SMALL_BAND`), moves its camp to the
   hex beside that is farthest from the raiders' home and not held by a band in feud with it; failing that, it joins kin
   whole. The jump is like `spreadYear`'s, the same step. It leaves what it cannot carry (`leftBehindKg`); its houses,
   fields, palisade are lost (`leaveStructures`, already in `driveOff`).
3. **The old hex is shunned.** `keptFrom` is read wherever a band chooses a hex now: `findHome` (buds), `spreadYear`
   (households and whole moves), and the move in step 2. So no band of the beaten lineage goes back while the grudge is
   above `FEUD`. The grudge already decays; the return comes when it has.
4. **The winners take the hex only if they are settled.** A raiding band that lives fixed (`livesFixed`) and is
   crowded may move households in (through `spreadYear`'s seepage, which it already does to an empty hex). A light band
   does not: the hex lies empty, the buffer. Whether the raiders also shun it is open:
   see section 5.

What this needs from the old code: nothing of Peril or PlanWayOut. It is the raid path plus the spreading path.

## 4. Proposal: sudden danger and ruin

**Sudden danger** (flood, fire, a storm if they come back): the band leaves for a week or a few, a short way, and
returns to the same hex. This is the one case where the #691 machinery fits as it is:
- the Peril interface stays (`perilAhead`, `strikeWeeks`); flood and fire become perils as the storm was, so they are
  foreseen as far as people foresee them (rising water: hours to a day; a grass fire: smoke on the horizon);
- a band whose camp is in peril takes the timed way out (`searchWayOut`) over the #696 going, to the nearest ground free
  of it within its week's reach, regardless of who holds that hex (a few weeks as guests, counted apart);
- it keeps its home hex and goes back when the peril has passed. A tied band leaves its house standing; the flood or fire
  may destroy it (whether they do now is unchecked).
- Weeks the step takes over are not recorded for training (stacked minds rule).

So Phase 7 of band-movement-removal.md should keep Peril, PlanWayOut and HexHurricane, not delete them.

**Ruin** (the ground fails for years): this is `spreadYear`'s lean-days branch already, slow by design. Faster would be
reading the band's reserves each season instead of the yearly `fatLow`. Not proposed now: the hunger and lean figures
(near707: hunger 11/1000 by y3) should be read first.

## 5. Open questions for Olli

1. Defeat: what share of households leave for kin? A fixed rule (e.g. half of them when driven off) or tied to the
   loss? (I would tie it to the loss: share of grown people killed x 2, capped.)
2. Should the beaten band's allies (bands that stood with it, `Helper`) take refugees first, before kin?
3. Buffer: should the raiders also shun the hex, so it stays empty for a while (as observed), or leave that to chance?
4. Sudden danger: refugees in an occupied hex for a few weeks break one-band-per-hex. Acceptable, or must they join
   the host band for those weeks?
5. Build order: defeat first (main has raids at 68-152 a year and the driven-off gap now); floods and fires after a
   count of how many people they kill.

## 6. To measure before building (PC, short)

A census on main 4c7445ed, m5-1..3, 10 y, default nets, no storms:
- `BANDS_DRIVEN_FROM_HOME` a year; of those, how many are still in the same hex a year later (the gap in section 1);
- raid deaths a year and per raid;
- fire, flood and snow deaths of people per 1000 a year;
- `BANDS_RELOCATED` and `HOUSEHOLDS_SEEPED` a year, for scale.

If driven-off bands are rare, the defeat rule matters little for numbers but still matters for the map (buffers,
frontiers). If fire and flood deaths are small, section 4 can wait.
