# Keeping trained models usable as the model grows

Proposal, 2026-10-14. Skills, tools, actions and features will keep being added. Trained networks must keep working beside the new mechanics, both to compare against and to continue training from.

## The problem today

Networks are saved as bare weight matrices. When a feature or an output is added, the sizes no longer match. `SharedMover.widen` patches one case (new place features appended before the kind's part), but it is fragile and covers only the movers.

## Proposed solutions

1. **Named inputs and outputs.** Every saved network carries the names of its inputs and outputs (for example `place:water`, `memory:fed`, `ways:sowWill`), not just their sizes. On loading, inputs and outputs are matched by name:
   - a new input gets zero weights, so the network ignores it until trained;
   - an input that no longer exists is dropped;
   - a new output gets zero weights in its last layer.

   This replaces `widen` for all networks (movers, ways, teachers).
2. **A neutral default for every addition.** A new feature has a value that means "nothing new here". A new choice has a hand-written default that a zero output selects (the band's ways already work like this: 0 is the scaffold's choice). An old network then plays the new model with the old behaviour, and the hand-written default for anything it was not trained on.
3. **Switches for mechanics.** (Superseded 2026-10-16: a mechanic that has replaced the old way is simply on, and only features still being tried keep a key in the config file, see `config.example.properties`.) Each new mechanic sits behind a run-time switch. An old network can then be run with the new mechanics on or off, and the effect of each mechanic is measured on its own.
4. **Side by side in one world.** Bands or animals driven by different networks share one world, dealt out round robin as `HexEvolveWays` does with its variants. A comparison is then fair, with the same weather, prey and neighbours. The check reports each version's scores separately.
5. **Continuing from a checkpoint.**
   - Load the old network, expanded by name as in point 1.
   - Train only the new rows first, with the old ones frozen for a few rounds.
   - Then train everything at a lowered learning rate.
   - Optionally, start with a few rounds of imitation of the old network (distillation), so behaviour does not jump.
6. **A registry of baselines.** `runs/baselines/NAME/` holds the networks with their input and output names, the commit, the settings, the seeds and the check's record line. The check and the trainers load any of them by name (`-Dhex.nets=baselines/natufian-1`). The Natufian baseline planned for tomorrow is the first entry.
7. **A regression suite.** Fixed seeds and maps, run with every baseline and with the hand-written behaviour, comparing the record line. It flags any change in an old network's results that a new mechanic causes.

## Order proposed

1 and 2 first (they make everything else possible), then 6, which the Natufian baseline needs, then 5, 4, 3 and 7.

## Status (2026-10-14)

Built:
- **Named inputs and outputs** (`learning/Schema.kt`). Every network is saved with a `.names` file and loaded by name. Movers, teachers and the bands' ways all go through it. Networks saved without names (all before today) are read as today's first names; a mover's kind part is read from the end. `SchemaTest` checks that a refitted network chooses exactly as before and gives zero on whatever is new.
- **Neutral defaults:** the rule in `Schema`'s documentation. New features read 0 where nothing is new, and new choices take their hand-written default at an output of 0.
- **A registry of baselines** (`tools/Baseline.kt`):
  - `baseline save NAME FROM_DIR [--ways FILE] [--commit HASH] [--note TEXT]`, `baseline list`, `baseline show NAME` (what each network lacks against today's model);
  - kept in `runs/baselines/NAME`, and never overwritten;
  - `nets=NAME` in the config file loads one by name.
