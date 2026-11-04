# Profiling

Design: `/mnt/project-files/plans/profiling-framework-design-2026-11-01.md` (project files). All of its plan is built: the recording, `ProfileReport`, and the old `Profile.part/lap` calls migrated to labels. `profile=fine` is gone: what it gave beyond labels (time split by kind, the slowest jobs with what they were about) is in the recording.

## Run with a recording
Set `profile=jfr` in `inflation.properties` (a config key, no switch). The run writes `rec.jfr` in the run directory when it exits. The settings are `src/main/resources/profile.jfc`: execution samples (2 ms), monitor and park waits over 1 ms, GC, JIT compilations (all) and failures, CPU load, allocation samples, and the model's own events:
- `inflation.Phase`: each phase of the week, from `Profile.time` (the time axis).
- `inflation.Par`: each `Par.forEach` call: jobs, summed job time, longest job; its duration is the loop's wall time.
- `inflation.Job`: one job over 1 ms (loop name and index).
- `inflation.Kind`: a part's time for one kind over a phase, from `Profile.kind` (jobs and their summed thread time), committed as each phase ends.
- `inflation.Slow`: a job among the 8 slowest of its kind so far, from `Profile.slow`, with what it was about (after the run's first 90 days).
Cost measured on the PC: about 1-2% of the step. Read the file with `jfr print --stack-depth 64 --json --events ...`; the default cuts stacks to 5 frames.

## In the model code
- `@Part("name")` on a function labels it for the report. It does nothing when the code runs. Names are unique (`PartNamesTest`). Do not label an `inline` function.
- `Par.forEach(name, n) { i -> ... }` (and `forEachPlace`, `forEachHex`, `forEach(name, list)`) is the model's parallel loop. With profile off it is `IntStream.range(0, n).parallel().forEach`, inlined; with `profile=jfr` it records the events above. Every parallel loop in `world/` and `ecology/` goes through it (`ParLoopsTest`).
- `Profile.kind(part, kind) { ... }` books a block's time to a part for a kind (a kind of animal, or bands), where sampling alone would lump the kinds together; `Profile.kindLap(part, kind, since)` does the same for a stretch of a loop. Without a recording they cost nothing but the call.
- `Profile.slow(what, Profile.mark(), day) { detail }` records a job that ranks among the slowest of its `what`, with the text `detail` gives (built only for one that ranks).
- Anything else worth its own line in the report is a function with a `@Part` label: there is no other timing in the model code.

## Reading a recording
`./gradlew runTool -Pmain=inflation.tools.ProfileReportKt -PtoolArgs="rec.jfr"` prints: reliability warnings (other processes on the machine, too few steps, JIT failures); each phase's wall ms per step with its share of the samples and the share on the main thread (high means serial); each `Par.forEach` loop's wall time, jobs, mean and longest job and its parallelism (summed job time over wall time; a loop whose wall time is close to its longest job is bound by that job); where the samples fell (innermost `@Part`, else the loop, else the phase), and for the parts that run mostly on the main thread the methods and lines the samples fell on (a library method with the line of the model that called it); time by kind for each part that books it, with jobs and mean job time; the slowest single jobs with what they were about (one a garbage collection fell in is marked: the pause, not the job, made it slow, and such jobs can fill the list); monitor waits by where they happened; GC; JIT failures with method names. The first 6 steps are left out as warm-up (`--skip-steps N`).
`--compare A1.jfr,A2.jfr B1.jfr,B2.jfr` gives the two arms' means (phases, loops, and time by kind) with their spread and says whether a difference is beyond it. Run each arm at least twice, back to back.

## Effect per cost
Design: `review/2026-11-04-effect-per-cost-design.md` (project files). A profile says what a part costs; this says what it does for the model, so that a part costing much and doing little can be removed or simplified.
- `t.ablate=key:form,...` (a tuning key, diagnostic only) switches mechanisms to a simpler form: `off`, `coarseN` (run once in N weeks, over N weeks' time) or `frozenN` (reuse its output for up to N weeks). Each mechanism, and the forms it offers, is an `Ablation` (`Ablate.kt`) with a guard at its one place in the model; what "off" or "less often" means is decided there, for that mechanism. Empty, nothing changes. Unknown keys or forms stop the run. A run with it prints what was ablated in its yearly output (for `huntingOdds`, how often odds were reused).
- `testWorld ab-arms --arms A.properties,B.properties --null NULL.properties [--years 10] [--replicates 2] [--parallel N]` runs the base, a null arm (a change known to matter next to nothing, e.g. `t.humanOther=1.001`) and each arm on the test worlds, paired by weather, and writes `arms-report.txt`: for each headline number (heads of every kind, people's hunger and all deaths per 1000, how people live) each arm's effect against the base, against the null arm's largest effect over the same pairs (the floor: how far runs drift apart on their own); the other series beyond the floor; and nominal-limit findings the arm has and the base does not. `testWorld arms-report DIR --arms A,B --null NULL` writes it again.
- The CPU an arm would save is read from a profile of the base (`ProfileReport`, a part's share of all samples), not from the arms' runs, which run many at once.

