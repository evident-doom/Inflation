# The ecology at a glance: what matches life, what is missing

Review, 2026-10-12, of the model on branch `claude/project-thread-6gdbsm` (weekly ranges, 15 land
species and 5 sea species, fields for plankton, fish, insects, rodents, reptiles and soil life).
Judgements about real ecology are from general knowledge, not checked against sources.

## Where it matches real life

**Body size sets the economy.** Food needs scale with M^0.75 (Kleiber), starting densities follow
Damuth's rule, fat stores with M^0.25, and big guts digest coarse fodder better (Jarman-Bell). This is
why aurochs ride out the dry season on straw that starves a gazelle, and why there are thousands of
roe for every bear.

**Populations are held by several forces at once, as in nature:**
- The lean season's food: standing dead grass is a finite stock that builds up and is eaten down.
  This is the "dry-season bottleneck" of savanna grazers.
- Crowding disease.
- Predators, which take mostly the young, the weak and the surprised.

No single knob does it. In the test runs every herbivore levels off at plausible densities, with
roe and boar commonest and aurochs rarest.

**The landscape of fear.** Herds trade food for safety. They sense the danger over a range, avoid it,
and bunch up and watch at a cost to grazing. A herd aware of hunters on its own ground (ibex on
cliffs, boar in thickets) is hard to take; predators need surprise, cover or weak prey. Hunger
makes animals bolder, mothers defend young, and thirsty or starving animals fight poorly. These are
the main themes of behavioural ecology, and they emerge from the rules rather than being scripted.

**Predator guild structure:**
- Lions rob everyone.
- Hyenas mostly scavenge.
- Jackals live on rodents and fawns.
- Stronger predators kill weaker ones.
- Packs hold territories.
- Kills draw thieves.

Predator biomass comes out near 1% of prey biomass, about what field studies find.

**Seasons drive everything:**
- A spring grass flush, deciduous browse, and autumn fruit and mast.
- Fattening and hibernation.
- Birth seasons that drift towards the best food for the young.
- Insects and reptiles dormant in cold and drought.
- Animals sensing coming weather.

**Migration from memory, not from scripting.** Herds carry a learned and inherited map of where each
month was good. Routes can be lost when a lineage dies, which matches how ungulate migrations are
now understood to be culturally transmitted.

**Water.** Drinking range, water from fresh food, springs and oases in dry country, and herds pulled
to rivers in the dry season.

**The sea.** Productivity where wind-driven upwelling and shelf edges bring up nutrients, not just
everywhere near shore.

## Where it falls short (largest first)

1. **Plants are a backdrop, not a partner.** The edible stocks are fixed shares of plant biomass
   (calibration constants). There is no forage quality: protein falls as grass dries, and that,
   more than quantity, limits small grazers. There are no plant defences. Above all, herbivores do
   not shape the vegetation. In nature, browsing stops trees regenerating, grazing makes lawns,
   boar rooting churns the soil, and herbivory together with fire keeps open country open. Here
   animals barely change the plants, so the woodland and grassland mosaic cannot shift under
   grazing, or later under people.

2. **No nutrient cycle.** There is no nitrogen or phosphorus: dung, urine and carcasses do not
   fertilise, and sea and land do not exchange nutrients (seabird guano, salmon-like runs feeding
   rivers' banks). Fertility only comes from plant litter.

3. **Missing middle of the food web.** None of these exist yet:
   - Hares, hyrax and porcupines; hyrax were the leopard's staple in the Levant.
   - Foxes, wildcats, badgers, martens and other small carnivores.
   - Vultures, the main carcass removers.
   - Birds on land other than eagles.
   - Waterbirds, and the huge twice-yearly bird migration along the Levant flyway.
   - Turtles and tortoises.
   - Otters.

   Many of these were everyday food for foragers. Today insects, rodents and reptiles are fields
   that feed predators but are themselves fed and limited only crudely.

4. **Disease is a density tax.** There is no transmission between species or groups, no epidemics
   that sweep a region (anthrax, rinderpest-like outbreaks), no parasites with their own dynamics,
   and no age, sex or genetic structure. A group is adults plus young, with no sex ratio, no old
   animals and no inheritance of traits beyond the season map and lineage tag.

5. **Calibration is from memory and only partly stable.** Species figures are recalled, not
   sourced. On the test islands lions die out on three of four, the first marine runs let dolphins
   boom and fish crash, and sea-bird colonies starve. The fixes are reasonable but tuned by eye on
   400 km islands, not checked against real densities or long runs on the full map.

6. **Space is coarse.** Ranges are summed on a 2 km grid as boxes, centres move in straight lines,
   and contact is an overlap rate. Real animals use corridors, passes, fords and edges, and there
   are no paths or barriers beyond water and travel cost. That matters most for migrations and for
   where people will meet animals.

7. **Fire and animals do not meet.** Vegetation burns, but grazers are not drawn to the green
   flush after fire and animals are not killed or driven by it, even though fire was a key tool of
   forager landscape management.

8. **The sea is thin.** It has no bottom life beyond shellfish, no seasonal spawning shoals at sea,
   no turtles, no tides, no estuarine nurseries, and krill is just zooplankton.

9. **Behaviour is mostly hand-written.** Learned policies exist (networks copying the baseline,
   and evolution of wolves, which found a small gain), but no species yet behaves better than its
   rules. Anticipation, learning and social information (watching other species' alarm) are thin.

10. **No humans.** The biggest factor of all for the project. Foragers would hunt, burn, gather,
    move herds and change all of the above, and the ecology has not yet been tested against them.

## What would most improve realism next

- **Two-way plant and herbivore coupling plus forage quality.** This makes vegetation respond to
  animals and gives grazers a real seasonal squeeze. It is also the ground on which human land use
  will act.
- **The small-game and scavenger layer** (hares, hyrax, foxes, vultures, birds), since it is the
  everyday food web of foragers.
- **Validation runs on the full map,** against a short table of real densities per species, with
  the numbers sourced.
