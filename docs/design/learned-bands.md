# One learned model for all bands

Proposal, 2026-10-12. Nothing here is built.

## Where we are

- **Animals** choose their weekly range with `BaselinePolicy`, a hand-written scoring of 26 candidate ranges.
  - A small network copy exists (`NetworkPolicy`, 21 features per option, trained by imitation).
  - Evolution strategies ran for wolves on the old daily model and need re-running on the weekly one.
- **Bands** choose their range with a hand-written scoring of their own (`scorePeople`). Everything else they do is a rule:
  - which herd to hunt, and how hard to gather and store
  - when to burn, build a kite, give, fight, marry out, copy a neighbour
- **Close to Natufian:**
  - Bands hold steady.
  - The age structure, elders and violence rates are plausible.
  - Mobility now has the right causes. A full-map check is running.
- **Not yet close to Natufian:**
  - Density is about 0.01 per km², against 0.1 or more for the Natufian.
  - The diet is woodland deer and boar rather than gazelle.
  - Disease is a heavy share of deaths.

## One model, every band

Yes: one shared model for all bands. This is how multi-agent learning is usually done (parameter sharing).
- **Differences go in the input.** Bands differ in what they observe: their possessions, skills, stores, traditions, known places and neighbours. The behaviour that maps those to choices is shared.
- **Every band is a source of data.** A full map holds 300 or more bands, each making a decision every week. A 10-year run gives about 180,000 decisions, which is enough for gradient methods.
- **Behaviour can differ without separate models.** The model sees the band's traditions and skills, so bands with different ways act differently.

## An architecture that grows with technology

The key choice: **describe things by their properties, not by name.**
- A tool is a vector of what it does: reach, killing power, speed, load carried, durability, what it needs to make.
- So are a skill, a tradition, a species and a place.
- A bow and a spear differ in their vectors, not in having separate inputs. A boat or a horse arrives as a new vector the model can at least partly read on first sight.

### Observations: a set of tokens, not a fixed vector
- **Self:** size, ages, condition, stores, season, moon.
- **Places to go:**
  - the weekly candidate ranges
  - named places it knows: water, stone, kites, caches, gathering sites
- **Others nearby:** bands (kinship, tongue, grudges, renown), herds (species vector, size, wariness), predators.
- **Its possessions:** tools, materials, skills and traditions, each a token built from its properties.

A set encoder (attention over tokens) handles any number of each and doesn't care about order. A new kind of thing needs a new token type, not a new network.

### Actions: choose among tokens
- **Where:** point at a place token. The movement physics underneath works out the route and its cost with whatever transport the band has (foot, boat, horse), so routing changes with technology without the model relearning it.
- **What this week:** hunt, gather, fish, make, build, trade, give, visit or avoid. Each is chosen by pointing at the target token (a herd, a band, a material).
- **How:** the "how" stays engineered. Hunting tactics depend on the weapons; the band chooses whom to hunt and with what effort, and the simulation works out the outcome from the tools it has. The model learns which option pays, not the ballistics.

This is a hierarchy: the model decides *what and where*, and engineered skills carry out *how*. New technology adds or changes skills underneath. The model sees their predicted outcomes and adapts.

### Growing the model over time
Ways to add capacity without starting again, roughly in order of how often they're used:
- **Property embeddings** (above): new items and skills cost nothing structurally.
- **Adapters:** small extra layers trained for a new domain (trade, farming, herding) while the core stays frozen. Then fine-tune everything gently.
- **Mixture of experts:** a router sends each decision to specialised sub-networks, and new experts are added as new domains appear.
- **Distillation:** train a bigger model to copy the old one, then continue training it.

None of these is a solved recipe for open-ended growth; that's research. But together they make "add capacity when the world gets richer" workable.

## What the model is trained to want

The honest objective is the one evolution sets: **lineage success.** A band's reward is the growth of its people and of the bands descended from it, measured in person-weeks. Splits count as success, not as loss.
- **Value head:** predicts the discounted future (log) population of the band and its descendants, at a discount of about 0.99 a week (a horizon of a few years).
- **No shaping for hunting well or settling down.** Those should be means, not ends. If we reward them we script them.
- **Auxiliary predictions** help learning and let us see inside the model: next week's food, next week's weather from what can be observed, the outcome of a hunt.
  - They also make up a learned world model. That's the seed for the long-horizon economic reasoning the project aims at: trade, planning, prices.
- **Longer horizons** (centuries) are left to selection in the world. Bands that do better grow and split, and traditions spread by imitation. The learned policy and cultural evolution work together.

## Training plan

1. **A stable baseline:** Natufian-like density, mobility and diet from the hand-written behaviour.
2. **The interface:** observation tokens and action pointers in Kotlin, with logging of (observation, choice, outcome) for every band every week.
3. **Imitation:** train the model to copy the hand-written behaviour, so it starts somewhere sensible.
4. **Reinforcement:** PPO with the shared model, on the GPU in PyTorch, with the Kotlin simulation serving batches of observations and receiving actions (a local socket or shared memory).
   - Start on the 500 × 1000 km map for speed.
   - Run several worlds in parallel on the 16 cores.
5. **Animals** keep their hand-written or evolved policies for now. Co-training them later avoids a moving target early on.

## Open questions

- **Non-stationarity.** Every band learns at once, and the ecology answers back. Wildlife may collapse under learned hunters. That would be a real finding, but it can destabilise training. Remedies: slow learning rates, and training against a mix of earlier policies.
- **Credit across generations.** Lineage reward with a few-year horizon may not see the payoff of, say, conserving game. Selection in the world helps there.
- **Speed.** A full-map week costs about 0.7 s. PPO needs millions of decisions, which means days of simulation at this size. The smaller map and parallel worlds are what make it feasible.
