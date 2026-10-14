# Bands live by their choices

Proposal, 2026-10-14. Nothing here is built. It is the engineered "how" layer that
[learned-bands.md](learned-bands.md) leaves to the simulation, and the scaffold a learned model starts from.

## The rule

- **The world sets no limits on people that it does not set on animals.**
  - Food comes from the same generic mechanisms predators use: meeting food, catching it, handling and eating it, carrying it, and its spoiling.
  - Tools, fire, stores and knowledge change the inputs to those mechanisms. They never change a yield directly.
- **Everything a band does is a choice with a cost in time, effort or risk.** That covers who goes out, for what, where, when, with which tools, and what is kept, eaten, made or given.
- **The scaffold policy only has to barely survive.** Thriving, new niches, tools, patterns and knowledge are the learned model's job. People are not tuned further.

## Rules that become choices or mechanisms

| Now (a rule) | Becomes |
|---|---|
| Diet shares (35/12/4/49) and diet switching | The week's time budget, spent through the same optimal-diet mechanism as predators (`forage`) |
| `huntEdge`, `snares()`, `fishing()`, `gatherShare()` multipliers | Tool and skill properties that enter meeting, catch odds or handling time |
| `practise` raising a skill | Kept: a generic learning curve, where time spent on a task makes it faster |
| Larder rule (dry half the spare meat; store 40/30/30 seeds/fruit/roots) | Choices to process and store, paid in time, with spoiling per food |
| `CARRY_DAYS`, camp moves | Carrying load from body and baskets; moving camp costs a day's carrying |
| Tool take-up and drop chances (`TAKE_UP`, `DROP`) | Making a tool is a choice paid in time and materials; copying one needs seeing it used |
| Raids at a set rate (`RAID`) | A choice weighed against its risk |
| Kites and taming behind switches | Building or taming is a choice paid in labour; the payoff comes through the same mechanisms |

## The week's mechanics

1. **Time budget.** Adults have about six working days a week, children and elders fewer. The band splits them into activities:
   - hunt big game, which party and where
   - trap or snare small game
   - fish
   - gather, and which food
   - carry or move camp
   - make tools, process food, and store
   - rest, or visit kin
2. **Each activity runs through the predators' mechanism.**
   - Meeting rates come from density, ground searched (sense, speed, the party's numbers) and activity overlap.
   - Catch odds come from size, speed, stalking, cover, the prey's weapons and refuge, and the moon.
   - Handling time is pursuit plus butchery, cooking and eating.
   - Tools change one of those inputs:
     - a spear raises killing power;
     - a bow adds reach, so more stalks end in a kill;
     - nets and snares search without anyone present;
     - baskets carry more;
     - grinding stones and fire cut seed and root handling;
     - drying and smoking slow spoiling.
3. **Food on hand.** Food is carried, stored or eaten. It spoils at a rate that depends on the food and how it was kept. Eating from stores is a choice.
4. **Knowledge.** The band's inputs are what it knows: places, the seasons, where game was, what neighbours told it, its skills and its tools. Knowledge is gained by going and doing, and passed on in meetings.

## Under-used features to wire in

- **Species products:** `hide`, `sinew`, `bone`, `hornOrAntler`, `fur` and `winterCoat` are listed but feed nothing. They should become the materials for clothes, bowstrings, needles and points.
- **Species behaviour:** `fearOfPeople`, `followsPeople`, `boldShare`, `ferocity`, `foresight` and `hibernates` are each used in one place or none. They belong in meeting rates and catch odds (a bear asleep in winter, game that flees people, scavengers drawn to camps).
- **Land:**
  - caves, flint (`stone`), straw and dead wood are material and fuel sources;
  - springs are meeting places;
  - the moon is light for night work.
- **Weather:** wet and dry weeks change tracking, fire and drying.

## Increments

1. **People forage through `forage`.**
   - A default time split per week, in proportion to what each activity paid lately (the scaffold).
   - The diet shares and the switching rule go.
   - Tools enter as properties.
2. **Food on hand and stores.** Food is carried and stored and spoils by its own rates; the larder rule goes.
3. **Making things.** Tools and clothes are made from materials and time. The take-up and drop chances go.
4. **The policy outputs the time split** (and the targets) besides where to go. It is trained by evolution as the herds are, and fitness is surviving and growing.

**Cost:** one decision per band per week, about 1,300 bands, so cheap next to the herds.
