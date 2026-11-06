# Following groups through their choices

How to see what individual groups did and could have done, week by week: which bands run into trouble, and whether
they had a chance. The method is two passes over the same save. The simulation is deterministic from a save and
the tracing only reads, so the second pass follows the same course as the first (checked byte for byte on hist.csv,
2026-11-05).

## The pieces

- `GroupWatch` (`ecology/GroupWatch.kt`): the one door from the model to any tracing. The model calls it at three
  points: a group's choice as it is made (`chose`), a group's week of births and deaths (`outcome`), and the end of
  each week (`weekEnd`). With no trace asked for, `groupWatch` is null and the model calls nothing. A new trace is a
  new watch in its own file, with its own config keys and a case in `groupWatch`; the model code is not touched.
- The doom trace (`ecology/DoomTrace.kt`), the watch so far, with two config keys:
  - `doomList=true`: on stderr, each year the bands with 2 or more hunger deaths, a control sample (1 in 40 by id
    of the bands with none), and each band that dies out after hunger deaths. Also each year the lean table
    (`doomLean` lines): for every band, by the kind of country it is in and the month, the band-weeks and
    people-weeks, the weeks it drew on its fat, was fed under 0.9 and moved camp, and its hunger deaths.
  - `doomTrace=ID,ID,...`: on stderr, for each of these bands, each week as it chooses: its people, fed, fat,
    larder, last forage yield, whether restless; its reach and the hexes in it; the food its mover sees
    (`knownGame`) at home, at the place chosen (with crowding, whether a network chose, its mind), and at the best
    place in reach (and how many are 1.25x as rich as home); and by a least-effort search out to 4x its reach, the
    best place there and the effort to the nearest place 1.25x as rich as home. Then its kind of country and hex,
    its camp's mean forage yield over the last 4 weeks, its own record of the food it got this month and the 3
    months ahead (`Band.monthSupply`, share of need), its foreseen lean days and survival, and how fed it remembers
    being at this hex in this season (`PlaceMemory`).
- The peril trace (`ecology/PerilTrace.kt`), `perilTrace=true`: on stderr, every band that foresees a deadly peril at its
  camp (`peril #` lines, the hurricane test so far), each week as it chooses: the peril at its camp (1 = it strikes this
  week, and a band whose camp is struck dies whatever it chooses); its reach, the hexes in it free of the peril and the
  effort to the nearest; the place it chose, its peril, survival steps and hand-written score; and of the places it weighed,
  the safest's survival steps, how many free places it weighed, how many of those were not open (deadlier than the safest
  by more than a step, which the draw and the planner never take) or had no score, and the best open free place; and the
  effort from its camp to the nearest free ground within 6 weeks' reach (`way out`, [searchWayOut]), the least of it
  from any hex in this week's reach (or "no progress"), and the effort of the first step on the way out. A `perilParts #` line splits the deaths it weighed (shares of it
  this week: peril, crossings, the place's own, leaving its camp) at its camp, at the place chosen, at the weighed place
  nearest the way out and at the safest free place. At the
  week's end a `perilEnd #` line says whether it lived. One pass is enough: the trace prints every warned band.
- `scripts/doomtrace.py`:
  - `pick OUT`: from a pass-1 output, the `doomTrace=` key for pass 2 (bands that died out, the hungriest living,
    and controls; `--died N --hungry N --control N`).
  - `summary OUT [OUT ...] [--bands]`: from pass-2 outputs, each followed band's weeks in cases (better ground in
    reach and stayed / moved, better only beyond reach, nothing better; not restless; reach holding only its own
    hex), all weeks and hungry weeks (fed < 0.9) apart, doomed against control, by arm (the run name before its
    first `-`).
  - `lean OUT [OUT ...]`: the lean table summed over years and runs, by kind of country and by country and month.
  - `peril OUT [OUT ...] [--bands]`: the warned band-weeks in cases (no free place in reach; went to a free place; free in
    reach but none weighed; free weighed but none open; an open free place, but it chose a perilous one, by mind, net or
    hand, staying or moving), and the bands killed with their camp struck by the case of their week before and by their
    whole warned stretch.

## The method

1. Pass 1: run the worlds from their saves with `doomList=true` (and the usual census). Keep each run's full output
   and its exact command and config.
2. `scripts/doomtrace.py pick OUT` for each run gives its `doomTrace=` key.
3. Pass 2: the same command and config with that one key added. Check that the end people, the doomList lines and
   hist.csv are identical to pass 1; if not, the trace changed the run (a bug in the watch) and nothing it says holds.
4. `scripts/doomtrace.py summary OUT ...` and read the bands one by one where the numbers ask for it.

On the pod the runner keeps pass 1's outputs under `/runs/<name>/<run>/`. A run spec for pass 2 names the pass-1
run, the commit (any commit whose simulation output is identical works; the same commit is safest) and one
`doomTrace=` key per run, and asks for the "doom #" and "doomList" lines and the identity check.

## What it can and cannot tell

- "Better" is the mover's own food estimate, not the yield it would forage there; 1.25x is an arbitrary line.
- Crowding is read from the band's sensed hexes, so it means little far off.
- A control group is needed before calling anything a cause: compare the doomed bands' cases with the controls'.
- Findings so far: review/2026-11-06-doom-trace.md in the project folder.
