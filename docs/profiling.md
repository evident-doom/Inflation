# Profiling

Design: `/mnt/project-files/plans/profiling-framework-design-2026-11-01.md` (project files). Step 2 of its plan is built here; the report tool (step 3) is not.

## Run with a recording
Set `profile=jfr` in `inflation.properties` (a config key, no switch). The run writes `rec.jfr` in the run directory when it exits. The settings are `src/main/resources/profile.jfc`: execution samples (2 ms), monitor and park waits over 1 ms, GC, JIT compilations (all) and failures, CPU load, allocation samples, and the model's own events:
- `inflation.Phase`: each phase of the week, from `Profile.time` (the time axis).
- `inflation.Par`: each `Par.forEach` call: jobs, summed job time, longest job; its duration is the loop's wall time.
- `inflation.Job`: one job over 1 ms (loop name and index).
Cost measured on the PC: about 1-2% of the step. Read the file with `jfr print --stack-depth 64 --json --events ...`; the default cuts stacks to 5 frames.

## In the model code
- `@Part("name")` on a function labels it for the report. It does nothing when the code runs. Names are unique (`PartNamesTest`). Do not label an `inline` function.
- `Par.forEach(name, n) { i -> ... }` is the model's parallel loop over `0 until n`. With profile off it is `IntStream.range(0, n).parallel().forEach`, inlined; with `profile=jfr` it records the events above. Use it instead of a bare parallel stream for loops over groups, bands, places.
- The older `Profile.part/lap/mark/slow` (`profile=fine`) still work; they are to be replaced by labels as functions are migrated.
