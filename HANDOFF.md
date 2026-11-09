# Handoff: packs, contacts and engagements (9 Nov 2026)

**Read this, act on what you need, then delete this file in its own commit** (`git rm HANDOFF.md`). It describes the
state on one day and goes stale.

The full record of the work, with every measurement and source, is `docs/design/pack-performance.md`. This file is
only what you need to pick up from where it stopped.

## Where things are

- Work was done in the worktree `C:\Users\olli.tuomi\IdeaProjects\inflation-stay` on branch `bands-stay`, and
  fast-forwarded onto `main` after each step. The last piece, keeping out of the stronger's way (`t.packsHide`), was
  built in a second worktree, `C:\Users\olli.tuomi\IdeaProjects\inflation-hide` (branch `pack-hiding`), and merged
  onto `main` with this file.
- Once you no longer need it, remove the second worktree: `git worktree remove ../inflation-hide` from
  `inflation-stay`, then `git branch -d pack-hiding`.
- All new model behaviour is behind switches, off by default. With all of them off, results are the same as before
  this work began, except for the recalibration of violence between bands (below), which is in the base model.

## The switches (all `t.*`, in `config.example.properties`)

| switch | what it does | state |
|---|---|---|
| `bandsStay` | bands hold their home hex (older work) | A/B on the full map running, see below |
| `poolDrifts` | wolves' and lions' dispersal pool at once, drifting toward game | same A/B |
| `huntersPressed` | people's pressure on the beasts of prey | done; nothing presses on today's worlds (people too thin); not in the A/B |
| `packsStay` | content packs hold their ground: footprints, contacts, engagements by strategy, yielding, two claimants per hex; staying bands' grounds; visits are all contact between neighbouring bands; raids settled as any meeting | same A/B |
| `bandStances` | bands' stances toward bands by regard; what bands saw of each other (also net inputs); a raid's party is half the able adults; neighbours greet by default | same A/B |
| `packsHide` | a hunter meeting another kind over twice its strength keeps out of sight and falls back; fleeing a much stronger kind succeeds with the slip-away chance | just built; runs unchecked (below) |
| `packStrategy`, `bandStrategy` | test settings: one fixed strategy for every pack or band | for tests only |

## Running when this was written (on this machine)

1. **Full-map A/B of the switches**, in `inflation-stay`, terminal tab "c4": `build/prof/abrun.cmd`.
   - Settles the test worlds `m5-1`, `m5-2`, `m5-3` (1000x2000 km, 30 years each, about 20 minutes each) into
     `~/.inflation/test-worlds/`, then runs `testWorld ab` twice:
     - base `build/prof/abbase.properties` (nets, every switch off) against variant `build/prof/abvariant.properties`
       (`bandsStay`, `poolDrifts`, `packsStay`, `bandStances`), 3 years, 2 replicates, at 6 days: report in
       `build/prof/ab6/`;
     - the same at 60 days (`abbase60.properties`): `build/prof/ab60/`.
   - Logs: `build/prof/absettle.log`, `ab6.log`, `ab60.log`; `build/prof/ab-done.txt` appears when all is done.
   - **Note:** the merge of `pack-hiding` lands in `inflation-stay` too. If the A/B rounds start after it, they compile
     the merged code. That is fine: `packsHide` is off in both arms.
   - **What to look for:** leopards and cheetahs (leopards fell about 9% on the larger world once bands met packs over
     their grounds), raids on homes (rose on one seed with `bandStances`), people's deaths and the line "killed in
     raids this year, per 1000 people", and the step's time. If it holds, the plan was to make the four switches the
     default and remove the old paths (the planner for content packs, per-hex meetings for groups that hold ground,
     chance contacts between staying bands). Tell the other machine before the defaults change.
2. **`packsHide` check runs**, in `inflation-hide`, terminal tab "c5": `build/prof/hide.cmd`.
   - Build and tests; switch off (`hoff6.log`, should match `inflation-stay/build/prof/s10on6.log` but for timing
     lines); on at 6 and 60 days (`hide6.log`, `hide60.log`); the larger world off and on (`hoffbig.log`,
     `hidebig.log`); `build/prof/hide-done.txt` when done.
   - **What to look for:** leopard and cheetah numbers and their causes of death ("young killed by lion", "killed by
     lion": 31-33% of leopards' deaths and 41-54% of cheetahs' before), and that wolves, lions and people barely move.
     Write the results into `docs/design/pack-performance.md` under a new section "Keeping out of the stronger's way".

To compare two logs without the timing lines:

```bash
f(){ grep -v "ms\b\|BUILD\| s;\|seconds\|took\|elapsed\|limits" $1; }; diff <(f A.log) <(f B.log) | wc -l
```

## Open items, in the order proposed

1. Read the full-map A/B; if it holds, make the switches default and remove the old paths.
2. Read the `packsHide` runs; if good, A/B it the same way and fold it into the defaults.
3. Who is present, the rest: a beast meeting a band meets one foraging party, not the whole band; a band's camp is
   weaker while its raiding party is out.
4. A small background homicide rate within bands, so the causes of death read true (most killing among foragers is
   within the band; none is modelled).
5. Training: retrain the hunters' network on restless weeks only (content packs no longer choose); a network for the
   bands' stances (`StanceInputs` is laid out for it; `regardStance` is the hand-written rule it would replace).
6. Phase 6 of `docs/design/band-movement-removal.md`: retire the people mover and ways inputs, with the other machine.
7. Smaller speed-ups: the sense of direction only where something roams (about 5 ms a step), the serial stand-in work
   in meetings.
8. Known issues: hunger about a fifth of people's deaths at 60-day steps on the small world; the ties gap at 60 days.
9. For later (Olli): pillaging, burning, luring; more rules for violent encounters in general.

## Practical notes

- Runs go through `.cmd` scripts in `build/prof`, started in the Terminal panel; the sandbox blocks Gradle's loopback
  connection, so Gradle cannot run from the agent's own shell. Scripts need CRLF line endings.
- **Do not name a script `con.cmd`** (or `prn`, `aux`, `nul`): Windows reserves those names, and the script silently
  never runs.
- Pushing and fast-forwarding `main` from `inflation-stay`:

```bash
GIT_TERMINAL_PROMPT=0 timeout 100 git push -q origin HEAD:bands-stay; cd ../inflation && git fetch -q origin && git merge-base --is-ancestor refs/remotes/origin/main refs/remotes/origin/bands-stay && git push origin refs/remotes/origin/bands-stay:refs/heads/main && git fetch -q origin && git merge -q --ff-only refs/remotes/origin/main
```

- The tests hold the code to its rules: no `!!`, no `Hex(Int)` outside listed files, hexes as `Hex` not `Int` (a
  parameter named `h` must not be an `Int`), no maps keyed by text, every tuning key in `config.example.properties`,
  no code reaching up a layer (`Tuning` may not use the ecology), every band field with a split and join rule
  (`BandCarry`), every stock table listed, test names without `:` or `;`.
