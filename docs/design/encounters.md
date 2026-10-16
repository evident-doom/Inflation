# Encounters and what hunters know

Status (2026-10-16): Olli approved the herds' side by the same rule; being built.

## Why

The probe on 2026-10-16 (`review/2026-10-16-training-for-migration.md` in the shared folder) found
that herds are killed almost only in the weeks they move: they meet hunters 3.5-12 times as often as
in weeks they stay put. Two core mechanics make this so, and neither is a choice the herd can make
better:

1. **Sharing a hex is a sure meeting.** `meetChance` (`HexMeetings.kt`) adds both sides' swept
   areas, multiplies by `TRACKING` 8, floors the overlap at `SPAN_FLOOR` 0.5 day, divides by the hex
   (about 87 km²), caps the result at 1 and then compounds it over the herd's bands. A wolf pack and
   an onager band that are in one hex for half a day search about 430 km² of an 87 km² hex, five
   times the cap (arithmetic from today's constants). On the small world about 99% of draws were 1.
   So every hex a herd crosses that a hunter also crosses is a meeting, however short the crossing.
2. **Hunters know where every herd is, every week, at no cost.** `herdPrey` reads `headsHere`, this
   week's true head count of every kind in every hex, and hunters and bands value every place within
   their reach by it (`valuePlaces`, `campValue`, the nets' input 0). They walk straight to the herds.
   Herds in turn see `predatorKg`, the true weight of hunters in every place in reach (hand score
   `SHUN_HUNTERS`, nets' input 3). People's input 3 is blurred by tracking skill; their game value
   (input 0) is not.

Together these mean a herd's only defence is to not move. Mechanism 2 also means no learned choice
of the hunters (where to look, when to wait) is rewarded, because finding prey takes no skill.

## Change 1: encounters as a rate (the gas model)

Two groups moving about the same ground meet at a rate set by how fast they close on each other and
how far they detect each other (Hutchinson & Waser 2007, the "ideal gas" encounter model):

    expected encounters  λ = 2 r v T / A

- `r`: the hunter's detection distance, its `senseKm` (people: × their tracking edge, as today).
- `v`: their relative speed, `sqrt(va² + vb²)` with each side's `dailyTravelKm` (the mean for random
  headings is within about 10% of this).
- `T`: days both are in the hex, from their timetables (`Meeting.span`), times `SEARCHING` 0.3 (the
  share of the day spent looking), times the existing `hours` overlap of their active hours.
- `A`: the hex's area.

A herd agent stands for `bandsIn(herd)` bands spread over the hex, so its expected encounters are
`λ × bands`, linear, not `1 − (1 − p)^bands`. The count is drawn from a Poisson. Ambush ground
(`AMBUSH`), gatherings at springs (`gather`) and small herds hiding in cover (`HIDE_IN_COVER`)
multiply `λ` as they multiply the chance today.

What happens on a meeting stays as it is (`hunterMeetsHerd`: the herd may see the hunters first and
turn aside; otherwise a chase, the herd scatters, the hunter stops to eat a kill), except that the
number of tries in the chase is the number of encounters drawn, not `bands`. Satiation (`need`) and
stopping to eat a kill already give a type II response: a fed hunter lets herds pass, and a hunter
eating a kill is not searching. A big herd swamps the hunters about it as it does today (eyes, the
young in the middle), now without the compounding that made it a sure find.

**Removed:** `TRACKING` (a ×8 with no grounding beyond calibration), `SPAN_FLOOR`, the `min(1, …)`
cap, the compounding over bands, and `MAX_MEETINGS` (stopping to eat and scattering already end a
group's meetings; whether the cap was carrying step cost is measured, below). Replaced outright, no
switch, as with discomfort.

**What it changes, honestly.** By the same arithmetic a wolf pack and an onager band that share a hex
for a whole day still meet about 2 times in 3 (`λ` ≈ 1.2). A herd agent of ten bands is still found
almost surely. That is the physics, not a fault: a pack ranging over 87 km² for a day finds a big
herd in it. What change 1 fixes is the short overlap (a herd crossing a hex in two hours while a
hunter passes: `λ` ≈ 0.1 a band, against 1 today) and the number of tries. So change 1 alone will
narrow the moving-week gap, but most of it comes from who ends up in the same hex at all, which is
change 2.

## Change 2: hunters know only what they have sensed, found sign of, or remember

Olli asked for this on 2026-10-16 ("Hunters know where every herd is, every week, at no cost"
should also be fixed). A hunter's or band's estimate of herd game in a place comes from four
sources, the best one it has:

1. **Senses: its own hex and the six around it.** A week's ranging covers them, and a wolf's 8 km
   or a band's 10 km of sense reaches a hex over. Here it knows the true heads, as today.
2. **Sign: hexes it passed through lately.** Each hex keeps herd sign for each kind: heads × days
   herds spent there, fading by half each week (hoofprints last days, dung piles weeks; one step is
   the finest the model can tell apart). When a hunter's path crosses a hex it reads the sign there
   and keeps it, aging, for two weeks. People read sign as well as their tracking skill lets them
   (`Skill.TRACK`, as `SIGN_GUESS` already does for input 3).
3. **Memory: where it did well at this season.** `PlaceMemory` already keeps per season what a group
   met and how well it fed. A hunter that killed on a calving ground last spring expects game there
   this spring. Herds that keep a round are found where they are predictable. This is where a round
   should cost something, and it is learnable for both sides.
4. **Expectation: elsewhere.** What the land suggests: each grazing kind's usual density for that
   habitat (its density last year per unit of `suits`), times `suits` there. Hunters go to good
   grazing and water, as real ones do. Without this, unknown country would read as empty and no
   hunter would explore. Bands keep `UNKNOWN_COUNTRY` on top of it.

Resident prey (`preyHere`, the density fields) stays as it is: residents are everywhere their
habitat suits, so a hunter's expectation of them is close to the truth. Bands keep sharing their
season places when they meet (`bandsMeet`, `PlaceMemory.hear`): word of game travels.

**The herds' side, by the same rule.** A herd sees hunters in its own hex and the six around it.
Beyond, it has the landscape of fear (`fauna.afraid`, kills there lately: exists, `FEAR_WEIGHT`) and
its memory of danger (`PlaceMemory.danger`: exists). `predatorKg` beyond the ring goes. Fixing the
hunters' side alone would leave herds with the perfect knowledge that hunters lose (Olli, 2026-10-16:
same rule for herds).

**For the networks.** Hunters' input 0 and herds' input 3 keep their names but now carry the
estimate, not the truth. Saved nets play on, reading a different spread. Training was stopped for this
fix and restarts from natufian-2 after it is merged, so the nets relearn on the new meaning anyway.
Open question for Olli: one new input, "how the group knows this place" (1 sensed, sign's age
fading toward 0, 0 expected), so a net can tell a hex seen empty from one expected full. The hand
score does not need it. Not included unless Olli wants it.

## What stays

Vigilance and being seen first (`VIGILANCE`, openness, `wary`), the chase (`CATCH`, `SURPRISE`,
`catchable`, `NOT_WORTH_TRYING`), big herds' eyes and shelter (`BIG_HERD_EYES`,
`BIG_HERD_SHELTERS`), slipping away from stronger hunters, denning, rivals and bands meeting (the
same rate formula, no `TRACKING`), and the order in which meetings are settled.

## What this does not fix

- Herds still choose their weekly path by effort alone (`stepEffort`); worn paths draw them, danger
  does not. That is route choice, paused, and may be needed after this.
- Whether migrating pays. This removes two reasons it cannot. It does not add what real migrants
  gain (leaving the lean season's ground to hungry predators, swamping them at calving). If the
  rounds check still shows rounds losing, the next question is those gains, not this.

## Expected outcome and risk

Hunters will find less. `TRACKING` 8 was probably holding predators up; with it and their perfect
knowledge gone, some predator kinds may decline or crash, and herds may grow until food limits them.
By Olli's rule that is reported, not tuned away. If a predator crashes, the question is which real
process it lacks (scent trailing, waiting at water, following herds), not a new factor.

## Step cost

- Sign: one float per grazing kind per place, filled from the paths `meet()` already walks, faded
  once a week. Small.
- Knowledge: `placeValue` becomes the shared expectation (still once per place and kind); each hunter
  corrects it only for its seven sensed hexes, its sign hexes (about 20) and its season memory. A few
  dozen lookups a hunter a week.
- Encounters: one Poisson draw per pair instead of one Bernoulli. The pairs are found as today.
- Inferred: under 5% a step. Measured in the test run, and reported against the 2× bar.

## Test plan (one run, batched)

On Olli's PC unless katastrofialue is free; three arms from the same saved state on full-1..3, with
weather replicates, 3 years:

- A: integration head (base).
- B: change 1 only.
- C: changes 1 and 2.

Reported per arm: head counts per kind (the `ab` report), the migration report (height swing, share
above 1000 m, km from half a year before), kills per hunter kind per year and predators' fed, and
step cost. Piggybacked: `hex-rounds-check` on B and C (seeds 1-3, 8 years) with the moving/still
probe, to see whether the 3.5-12× moving-week gap and the rounds' losses shrink.

B is a throwaway commit for the measurement only; what merges is C.

## Order

1. Olli reviews this.
2. Build changes 1 and 2 on one branch, with B as an intermediate commit.
3. The test run above.
4. Merge; the commit goes to the coordinator so the runner restarts training from natufian-2.
