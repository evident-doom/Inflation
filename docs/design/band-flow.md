# Bands as a flow over their ground: a plan

Written 23 Nov 2026 after the maxpop1123 sweep and the trace of why bands stop moving
(`runs/maxpop1123/summary.txt`). This builds on [band-movement-removal.md](band-movement-removal.md) and keeps its aim:
no weekly jumps and no costly search for a better hex. It replaces how a band follows its food and decides to settle.
It is a proposal for Olli to mark up.

**Status (23 Nov 2026):** step 1 built on `claude/band-flow` (uncommitted): `Band.spread`, `flowSpreads` (`Spread.kt`), gathering from the spread (`ForageWeek.spreadPart`), the census line "away from home by yearly rain", and the key `t.bandFlowWeeks` (4). A tied band (`livesFixed`) is drawn wholly home until step 3. First check (`runs/flow1123/summary.txt`, seed 1, 20 years): people +1.7%, food the same, step +5%; away from home 43–55% in the first years (60–82% on dry land), falling to 10% as houses spread and pin bands home. Step 3 built too (`housePays`, `Band.homeTime`, houses pull rather than pin, `homeShelter` by time at home, stores no longer lost by the spread): over 20 years on seeds 1 and 3 no band builds, and bands are away 70–75% of the time on every kind of land. With only warmth counted, a house is worth about 28 kg a person a year (5% of need), too little for a band at home 30% of the time; and nothing yet holds a band home on rich, steady land. With a house's food worth counted in full (`Band.houseFood`: warmth, stores kept, larder kept from raiders) and stores at home pulling a band home by the walks to fetch them (`storesFetched`), houses reach 47% (seed 1) and 28% (seed 3) of bands by year 20, still rising, and are rarest on dry land (15% and 0%, few bands there); people within 1% of main. Time away stays 62–71% on every kind of land.

## Why

The mobile test of [neolithic-path.md](neolithic-path.md), step 2, wants bands on poor, variable land to stay mobile.
In maxpop1123 it fails at every density, including at the food ceiling:

- Houses reach 75–89% of bands within 30–50 years at 1× and 4× alike. Dry land (under 300 mm) is no exception: of
  the bands left there, 40–100% have houses.
- Camp moves fall from about 3.3 a year to 0.2–0.4. The record is 5–60.

The cause is how the model is built, not what the nets learned:

- No network moves a band. A band that holds its home is pinned to its hex (`GroupPlanner`, `staysHome`).
- The whole camp moves only through `moveCamps`, once a month. A band moves to the hex beside if that hex gives 25%
  more food a head, and only if all of these hold:
  - it went hungry this year (`wantsFood`);
  - it is tied to nothing (`livesFixed`: houses, fresh sowing, a palisade);
  - its shelter is light;
  - it can carry all its stores.
- `buildHouse` only asks that the band has kept its camp half a year (less for a keen one), is fed and is stocked. It
  never asks how steady the place's food is. Since staying is the default, nearly every band qualifies after a good
  season.
- Once a band has houses it is `livesFixed`. It never moves for food again and does not shift camp, so houses only
  ever spread.

Two of Olli's constraints (23 Nov 2026) shape the fix:

- **No discrete hops to neighbouring hexes.** Their size per step would make behaviour depend on step length: a band
  could move one hex a step however long the step.
- **No scan of every hex within walking distance.** That scan was much of the old cost.

Instead: **a constant flow of people across hexes.**

## The idea in one paragraph

A band has a home hex and a **spread**: the share of its person-weeks in its home hex and in each of the six around it.
The spread flows each step towards where food a head is better, at a rate that does not depend on the step's length.
The band forages from its spread and meets other bands where their spreads overlap. Several things are then read from
lasting shifts in the spread, measured over months:

- **Settling:** a house is worth the share of time the band actually spends at home.
- **Drift:** the home follows most of the band.
- **Splitting:** part of the band can become a band of its own.
- **Joining:** overlapping kin can become one band.

In populated country most neighbouring hexes have a band of their own, so overlap, splitting and joining are the
normal case, not exceptions.

## What exists already

- **Footprints.** A band already has a footprint (`bandGround`, `layGround`, `Footprint.weights`): the hexes it works
  from camp, each weighted by its share of the band's time. The home share is a constant: 0.7 for a band on the move,
  0.85 for one living fixed (`BAND_MOBILE_HOME`, `BAND_FIXED_HOME`). The footprint's area is fixed too (314 or 79
  km²). The spread replaces those constants with a state that flows.
- **The residents' flow.** Residents already move by a one-step flow (`ResidentMoving`, `residentMoveWeeks`). Under the
  resident rule the bands' flow should share its form: a relaxation toward a target with a time constant.
- **Seeping and merging.** Households already seep between hexes (`PeopleSpread`, `SEEP_RATE`), as random whole
  parties. Bands merge and pair at meetings (`mergeAndPair`), split by quarrels past their easy size (`discord`), and
  bud (`spreadYear`, `splitOff`). All of them become cases of the flow below.

## The parts

### 1. The spread and its flow

- **State.** Each band holds seven shares, home plus its six neighbours, summing to 1. On flat open ground and
  undisturbed they settle near today's home share. Optionally a ring further out; see open question 2.
- **Target.** Each step the band's target spread weighs each hex by:
  - the food a head it would get there now, net of other bands' people and the residents' take (the band's own
    foraging, as now);
  - minus the extra walking: from a camp at home, a day out and back, so a neighbour's food is worth less by the
    hours lost.

  The weights go into a soft-max with the band's will to follow food as the temperature, so the target is smooth, not
  winner-takes-all.
- **Flow.** `share += (target − share) · (1 − exp(−dt / τ))`. A week's step and a month's give the same result over a
  year. Where `dt` is long next to τ, the step is cut into sub-steps. This is arithmetic on seven numbers, so it costs
  nothing to speak of.
- **τ.** τ is the band's readiness to follow food. It is a ways dial (`BandWay.FOLLOW`), so the ways network learns it.
  The scaffold's value is about 4 weeks (Kelly 1995: a camp kept some 4 weeks near the middle of the 5–60 moves a
  year).
- **Pull home.** Houses, stores, graves, fields and herds add to the home hex's weight by what leaving them would cost
  a head. This is a pull, not a lock: a band with houses can still empty out when hunger outweighs it.
- **Cost.** Seven numbers and six neighbours a band. No reach search.

### 2. Foraging and meetings read the spread

- **Foraging.** A band takes from each hex in proportion to its share there. This replaces the fixed catchment and
  home share in `bandGround`. Its draw on each hex's plants, game and water is shared with other bands and residents
  under the rules for sharing a hex that exist now.
- **Meetings.** Two bands meet in proportion to how much their spreads overlap: the sum over hexes of the smaller of
  their person-weeks there. Contacts, telling of country, exchanging partners and raids all read this, as the
  weekly-ranges design meant ("groups as ranges, overlap contact").
- **Census.** The census counts the share of person-weeks away from home, beside camp shifts within the hex. Against
  the record of 5–60 camp moves a year, a share away of 20–60% on poor land is the target.

### 3. Settling is reckoned from the band's own record

- **Worth.** A house's worth to a band is today's benefit times the share of person-weeks it has spent at home. The
  benefit is the food that keeping warm no longer takes, safety, and stores kept. The share is a running mean over the
  past years, its leanest months weighted most. The cost is the labour, stone and timber, as now.
- **What it replaces.** This replaces `buildHouse`'s gate of "camp kept 0.5 years / houseWill, fed, stocked". A band
  whose spread swings out every lean month finds a house worth little and does not build.
- **The house dial.** The band's keenness on houses (`BandWay.HOUSES`) stays a learned dial. It now weighs the reckoned
  worth instead of shortening a wait.
- **No more lock.** `livesFixed` stops gating movement. It only feeds the pull home in part 1.

### 4. Drift, splitting and joining

All three read running means of the spread, kept over months (a time constant of about half a year), never single
steps. So they do not depend on step length and do not flicker.

- **Drift.** When most of a band's person-weeks over the past half year sit in one neighbouring hex, its home becomes
  that hex. The spread is re-centred on the new home. Its houses stay where they are and weather away unused (as now).
- **Splitting.** When a lasting share sits in a neighbouring hex, but not most, the households there become a band of
  their own with that hex as home, provided:
  - that share makes a viable band (at least `SMALL_BAND` grown people);
  - what stays behind is viable too.

  The leavers take their share of goods and stores, keep their ties to the old band, and carry its customs (as
  `splitOff` does now). Houses stay with whoever is at home. Budding becomes this case. The quarrels that split a band
  past its easy size can stay as they are, or push the split threshold down for an oversized band.
- **Joining.** Two bands join when both of these hold:
  - their spreads overlap heavily, as a share of each one's person-weeks;
  - their ties are close (kin or friends, as `mergeAndPair` asks now), most readily when one of them is small or short
    of hands.

  Overlap with bands that are not close raises hostility and the chance of contests instead. That is step 2c's
  "crowded country".
- **Hysteresis.** The join threshold sits well above the split threshold. A pair that has just split does not rejoin
  until their overlap has risen well past where they parted.
- **Households between bands.** Households flow steadily between overlapping bands, by food a head and ties: a rate ×
  `dt` × the difference, replacing seeping's random whole parties. Splitting and joining are the large cases of this
  exchange.

## What it should show

- **Dry land.** Spreads swing out to the neighbours in lean months and back with the rains. Houses are rare under 300 mm.
- **Seasonal gatherings.** Gatherings emerge without being scripted. In the dry season spreads close in on water,
  overlap rises, and close bands join or camp together. With the rains they disperse and split again. The !Kung and
  the Hadza lived this way (Lee 1979; Woodburn 1982; from memory).
- **Rich, steady land** (rivers, coasts, oak country). Spreads stay home, overlap only at the edges, and the bands
  build. That is step 2's aim.
- **Worsening land.** It empties out gradually, at the rate of the flow, without a hunger gate.
- **Cost.** A step costs no more than today's footprints. The overlap pass only touches pairs of bands in neighbouring
  hexes.

Measures, against maxpop1123 and the 30-year settles:

- houses by yearly rain;
- the share of person-weeks away from home, by kind of country;
- splits and joins a year, and band sizes over the year;
- violent deaths (step 2c);
- people per km² at the ceiling;
- the step's time.

## Order of work

1. **The spread and its flow** (part 1), with foraging reading it (part 2) and the census measures. On its own this
   already lets bands answer to worsening land.
2. **Settling reckoned from time at home** (part 3). This retires `moveCamps`' gates and `livesFixed` as a lock. The
   mobile test is read here.
3. **Drift, splitting and joining** (part 4), with the household flow. This retires seeping's draws. Budding and
   merging become its cases.
4. **Meetings read the overlap** (part 2's second half).
5. **Rebuild and retrain.** Rebuild the settled worlds (`ppo.settledWorlds`) and the test worlds, then train the people
   and ways nets afresh. The new `FOLLOW` dial and the changed meaning of `HOUSES` are new inputs, and they stay
   whatever an A/B says (the rule for qualitative changes).

Each new mechanism above goes to Olli before it is built. Tuning their values afterwards does not.

## Open questions

1. Should the spread be part of the people network's view (a band sees its own spread and the neighbours' food), or
   only the ways dials? The dials are enough for a start.
2. Should the six neighbours be the reach, or a ring further out for long steps and big, mobile bands? With sub-steps
   one ring should do: over a lean season a band can drift several hexes.
3. Should a split part keep a claim on the old home's houses and caches (possession, not ownership, as now), or start
   with nothing but what it carries?
4. Should joining also bring together customs and ways, as merging does now (the larger band's), or weigh them by
   people?
5. Should packs move to the same flow later, as band-movement-removal.md meant ("packs later, by the same route")?
