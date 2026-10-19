# Encounters and what hunters know

A design record: since model 3.0 plant-eaters live only as residents, so the herds' side below no longer runs.

Olli chose the same rule for herds and for people.

## Why

Two mechanics would make a herd's only defence to not move, and neither is a choice the herd can
make better:

1. **If sharing a hex were a sure meeting,** every hex a herd crossed that a hunter also crossed
   would be a meeting, however short the crossing. Herds would be killed almost only in the weeks
   they move.
2. **If hunters knew where every herd is, every week, at no cost,** they would walk straight to the
   herds. No learned choice of the hunters (where to look, when to wait) would be rewarded, because
   finding prey would take no skill. Herds seeing the true weight of hunters in every place in reach
   is the same flaw on the other side.

## Encounters as a rate (the gas model)

Two groups moving about the same ground meet at a rate set by how fast they close on each other and
how far they detect each other (Hutchinson & Waser 2007, the "ideal gas" encounter model):

    expected encounters  λ = 2 r v T / A

- `r`: the hunter's detection distance, its `senseKm` (people: × their tracking edge).
- `v`: for a hunter, its own `dailyTravelKm`; for two hunters, their relative speed `sqrt(va² + vb²)`.
- `T`: days both are in the hex, from their timetables (`Meeting.span`), times `SEARCHING` 0.3 (the
  share of the day spent looking), times the `hours` overlap of their active hours.
- `A`: the hex's area.

A hunter comes on a herd exactly as it comes on a band of resident game: over the ground it searches
(`searchKm2`: `2 r v × days × SEARCHING`, a bigger party fanning out as the square root of its numbers,
less of it seen in rain, haze or cover, `sight`), its share of the week in that hex. A herd is run into
as one. The count is drawn from a Poisson. Ambush ground (`AMBUSH`, herds walk the worn paths) and, for
two bands, gatherings at springs (`gather`) multiply `λ`.

On a meeting (`hunterMeetsHerd`) the herd may see the hunters first and turn aside; otherwise there
is a chase, the herd scatters, and the hunter stops to eat a kill. The number of tries in the chase
is the number of encounters drawn, at most one for each band (a band that was chased has
scattered). Satiation (`need`) and stopping to eat a kill give a type II response: a fed hunter lets
herds pass, and a hunter eating a kill is not searching. A big herd swamps the hunters about it
(eyes, the young in the middle). There is no cap on meetings: stopping to eat and scattering end a
group's meetings.

**What the rate means.** A wolf pack and an onager band that share a hex for a whole day still meet
about 2 times in 3 (`λ` ≈ 1.2). A herd agent of ten bands is still found almost surely. That is the
physics, not a fault: a pack ranging over 87 km² for a day finds a big herd in it. A short overlap
(a herd crossing a hex in two hours while a hunter passes) gives `λ` ≈ 0.1 a band. Who ends up in
the same hex at all is set by what hunters know, below.

## Hunters know only what they have sensed, found sign of, or remember

A hunter's or band's estimate of herd game in a place comes from four sources, the best one it has:

1. **Senses: its own hex and the six around it.** A week's ranging covers them, and a wolf's 8 km
   or a band's 10 km of sense reaches a hex over. Here it knows the true heads.
2. **Sign: hexes it passed through last week.** Each place keeps herd sign for each kind: heads × days
   herds spent there, fading by half each week (hoofprints last days, dung piles weeks; one step is
   the finest the model can tell apart). A hunter reads the sign in the hexes of last week's path. People read sign as well as their tracking skill lets them
   (`Skill.TRACK`, as `SIGN_GUESS` does for input 3).
3. **Memory: where it did well at this season.** This works through bands' season places and
   `PlaceMemory` (fed, danger), and packs' held ground.
4. **Expectation: elsewhere.** What the land suggests: each grazing kind's usual density for that
   habitat (its density last year per unit of `suits`), times `suits` there. Hunters go to good
   grazing and water, as real ones do. Without this, unknown country would read as empty and no
   hunter would explore. Bands keep `UNKNOWN_COUNTRY` on top of it.

Resident prey (`preyHere`, the density fields) is read as it stands: residents are everywhere their
habitat suits, so a hunter's expectation of them is close to the truth. Bands share their season
places when they meet (`bandsMeet`, `PlaceMemory.hear`): word of game travels.

**People, by the same rule** (Olli's choice): bands see game, hunters and other bands only in the
ring; beyond it they go by sign (as their trackers read it), memory, word from other bands and what they
expect. Raids go by homes a band knows.

**Each group's own kind, and stronger hunters** are seen by the same rule: crowding beyond the ring is
expected, and a hunter keeps out of a stronger kind's way only where it senses it.

**The herds' side, by the same rule.** A herd counts the animals hunters would kill as deaths in its
place score (`huntedAhead`, see `deathsAt`), not as a food-side fear. The hunting parties it would
meet on its way and where it grazes count where it senses them (its own hex and the six around it),
by the meeting rules at their odds (`herdKillsAhead`: encounters with waylaying on worn paths, being
seen first, prey switching, catchability, the worth of the chase, tries per band, the hunter's
week's want of meat). Beyond, it reckons on its kind's usual weekly losses to hunting parties
(`usualGroupKill`). The residents' hunters about the ground it would graze count by the rate they
hunted there this week (`HexFauna.residentKillOdds`). Without the sensing rule, herds would keep the
perfect knowledge that hunters lack.

**For the networks.** Hunters' input 0 and herds' input 3 carry the estimate, not the truth.
An open question: one more input, "how the group knows this place" (1 sensed, sign's age fading
toward 0, 0 expected), would let a net tell a hex seen empty from one expected full. The hand
score does not need it.

## Other mechanisms

Vigilance and being seen first (`VIGILANCE`, openness, `wary`), the chase (`CATCH`, `SURPRISE`,
`catchable`, `NOT_WORTH_TRYING`), big herds' eyes and shelter (`BIG_HERD_EYES`,
`BIG_HERD_SHELTERS`), slipping away from stronger hunters, denning, and the order in which meetings
are settled work alongside this. Rivals and bands meeting use the same rate formula.

## What this does not cover

- Herds choose their weekly path by effort alone (`stepEffort`); worn paths draw them, danger
  does not.
- Whether migrating pays. These rules remove two reasons it cannot. They do not add what real
  migrants gain (leaving the lean season's ground to hungry predators, swamping them at calving).

## If predators decline

Hunters find less than with perfect knowledge, so some predator kinds may decline or crash, and
herds may grow until food limits them. By Olli's rule that is reported, not tuned away. If a
predator crashes, the question is which real process it lacks (scent trailing, waiting at water,
following herds), not a new factor.

## Step cost

- Sign: one float per grazing kind per place, filled from the paths `meet()` walks, faded
  once a week. Small.
- Knowledge: `placeValue` is the shared expectation (once per place and kind); each hunter
  corrects it only for its seven sensed hexes, its sign hexes (about 20) and its season memory. A few
  dozen lookups a hunter a week.
- Encounters: one Poisson draw per pair.
