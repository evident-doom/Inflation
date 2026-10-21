# Keeping trained models usable as the model grows

Skills, tools, actions and features keep being added. Trained networks must keep working beside the new mechanics, both to compare against and to continue training from.

## The problem

Networks saved as bare weight matrices stop fitting when a feature or an output is added, because the sizes no longer match.

## Solutions

1. **Named inputs and outputs.** Every saved network carries the names of its inputs and outputs (for example `place:water`, `memory:fed`, `ways:sowWill`), not just their sizes. On loading, inputs and outputs are matched by name:
   - a new input gets zero weights, so the network ignores it until trained;
   - an input that no longer exists is dropped;
   - a new output gets zero weights in its last layer.

   This is `learning/Schema.kt`. Every network is saved with a `.names` file and loaded by name; movers and the bands' ways all go through it. A network saved without names is read with the first names; a mover's kind part is read from the end. `SchemaTest` checks that a refitted network chooses exactly as before and gives zero on whatever is new.
   **Net versions.** Apart from the model's, each role's networks (the mover and its ways network) carry a `major.minor` net version (`Role.net`, third line of the `.names` file). Raise the major by hand when a role's networks change structure (inputs, outputs, what an output means). The training's ecosystem check then keeps the first network trained under the new major as that role's baseline, whatever it scores against the earlier version, and later rounds compare against it as before. A minor changes nothing in the training; a net saved without a version counts as another major.
2. **A neutral default for every addition.** A new feature has a value that means "nothing new here". A new choice has a hand-written default that a zero output selects (the band's ways work like this: 0 is the scaffold's choice). An old network then plays the new model with the old behaviour, and the hand-written default for anything it was not trained on. The rule is in `Schema`'s documentation.
3. **No switches for mechanics.** A mechanic that has replaced the old way is simply on. Only features that runs really vary keep a key in the config file (see `config.example.properties`).
4. **Side by side.** A fair comparison uses the same weather, prey and neighbours. The training's ecosystem check (`learning/EcoCheck.kt`) runs each role's new network against its kept one, with the same world and weather, in separate runs. Mixing networks in one world, dealt out round robin as `HexEvolveWays` does with its variants, is the stronger form.
5. **Continuing from a checkpoint.**
   - Load the old network, expanded by name as in point 1.
   - Train only the new rows first, with the old ones frozen for a few rounds.
   - Then train everything at a lowered learning rate.
   - Optionally, start with a few rounds of imitation of the old network (distillation), so behaviour does not jump.

   PPO training carries on from the networks already in its `out` folder (`learning/PpoTrainer.kt`).
6. **A registry of baselines** (`tools/Baseline.kt`). `runs/baselines/NAME/` holds the networks with their input and output names, the commit, the settings, the seeds and the check's record line. Baselines are never overwritten.
   - `baseline save NAME FROM_DIR [--ways FILE] [--hunter-ways FILE] [--commit HASH] [--note TEXT]`, `baseline list`, `baseline show NAME` (what each network lacks against the current model);
   - `nets=NAME` in the config file loads one by name.
7. **A regression suite.** Fixed seeds and maps, run with every baseline and with the hand-written behaviour, comparing the record line. It flags any change in an old network's results that a new mechanic causes.
