# Kind by kind: are they living their niches? (night of 14–15 October 2026)

**How this was measured:**
- **Runs:** hand-written behaviour on a 2000×1000 km world. Seed 1 ran for 15 years (commits a17d280 to 236949e plus the kind-by-kind report). Seed 2 ran for 40 years on the same balance.
- **Report:** the check's new kind-by-kind block. For each kind, it gives:
  - where the kind lives (its three main kinds of country, against each one's share of the land);
  - how well fed it is;
  - what the herds eat;
  - how far groups go in a year;
  - its deaths by cause.

The expected ranges are from memory of the literature. They are approximate.

## Summary

| Kind | Expected | Seen | Verdict |
|---|---|---|---|
| Roe deer | Woodland edges, forest | Temperate forest 44%, Mediterranean woodland 22% | Right. Disease is 29% of deaths, which is high. |
| Fallow, red deer | Open woodland, forest | Temperate forest about 50% | Right. |
| **Ibex** | Cliffs and crags, high ground | Temperate forest 33%, open woodland 20%, steppe 16%. 30% in the lowest height band. | **Wrong: lowland ibex.** The rugged preference loses to lowland food, and nothing makes the crags pay off (escape from hunters). |
| Wild boar | Wet woods, thickets | Temperate forest 47%, Mediterranean woodland 33% | Right. Fed only 0.69, and hunger is 12% of deaths, so it is crowded. |
| Aurochs | Wet lowland woods, floodplains | Mediterranean woodland 41%, temperate forest 27%. 94% in the lowest band. | Plausible. |
| **Hippo** | Rivers and lakes in warm country | Mediterranean woodland 58%. Cold is 62% of deaths. | **Marginal.** The climate is too cold for it in most places. Fine to let it die back where it does not fit (the niche policy). |
| Brown bear | Forest, mountains | Temperate forest 49%, conifer 19%, high bands | Right. Disease is 41% of deaths, which is high. |
| Elk | Wet boreal and temperate forest | Temperate forest 51%, steppe 14% | Mostly right. Declining over 40 years (255k to 115k). |
| Mouflon | Open rugged hills, scrub | Shrubland 43% | Right. |
| **Gazelle** | Steppe, semi-desert, open plains | Shrubland 68%, Mediterranean woodland 29%; steppe and semi-desert hardly at all | **Partly wrong:** missing from steppe and semi-desert. |
| **Onager** | Steppe, semi-desert | Shrubland 65%, Mediterranean woodland 21%, semi-desert 6% | **Partly wrong:** the same. |
| **Oryx** | Desert and semi-desert | Shrubland 77%, semi-desert 3% | **Wrong:** a desert antelope that lives in scrub. |
| **Dromedary** | Desert | Shrubland 68%, Mediterranean woodland 18% | **Wrong:** the same. |
| Hartebeest | Open grassland, savanna | Shrubland 87% | Acceptable. |
| Ostrich | Open plains, semi-desert | Shrubland 69%. Declining over 40 years (3,500 to 500). | **Declining.** "Other" is 43% of its deaths. |
| Wild horse | Steppe | Mediterranean woodland 54%, shrubland 43%; steppe 3% | **Wrong:** horses of the steppe that avoid it. |
| Reindeer | Tundra, boreal | Almost gone on the 28–55° maps | Expected: no niche at these latitudes. |
| **Lion** | Open woodland, savanna, scrub | Temperate forest 75–78% | **Wrong:** forest lions. |
| **Leopard** | Broken, wooded country | Temperate forest 88–91%. Extinct by year 20 on seed 2. | Partly right, but dies out. |
| **Cheetah** | Open plains | Temperate forest 48–57%. Hunger is 37–46% of deaths. | **Wrong:** forest cheetahs. |
| Wolf | Anywhere | Temperate forest 41–67% | Acceptable. Crashed at year 40 on seed 2 (776 to 218). |
| Striped hyena | Arid scrub, open country | Temperate forest 65–73% | **Wrong.** |
| Jackal | Anywhere, near people | Temperate forest 55–59% | Acceptable. |
| **People (Natufian)** | Mediterranean oak woodland and parkland | Temperate forest 67–69%, Mediterranean woodland 6–7% | **Wrong** for the Natufian. |

## Why

1. **Everything follows the densest food: the temperate forest.**
   - The resident deer, boar, elk and aurochs are densest there, at about 3,000–3,700 animals per 1000 km².
   - So hunters crowd there, and people with them, whatever their own preferred country.
   - Predators' habitat preferences (a cheetah's for open ground, a lion's for parkland) weigh little against prey value.
   - A first fix tonight made coursers lose success in thick cover (`COURSING_COVER`). Cheetahs got hungrier but did not leave the forest, so how they choose their ground has to weigh their country too.
2. **Desert and steppe herds avoid their country.**
   - Semi-desert has almost no edible forage in the model (0.05–0.11 kg/m², against 0.25–0.7 elsewhere).
   - The herds' hand-written movement goes where food is densest, so it ignores the advantages of dry country: fewer hunters, and needing little water.
   - Oryx, dromedary and gazelle should have the edge where others cannot drink (`tether`), but their scoring rewards rich ground more than safe ground.
3. **Ibex have nothing to gain from crags.**
   - Their preference for rugged ground is a weight on where they spread.
   - Crags do not save them from hunters enough to pay for the poorer food.
   - In reality, escape terrain is what holds them there.
4. **People** follow game and plants into the temperate forest. The record puts Natufian density highest in the Mediterranean woodland, with acorns, cereals and gazelle. That is the people role's choice to learn, but the hand-written scaffold points the wrong way.
5. **Grazing range rarely spreads.** Herds grazed over 1.0–1.3 places a week, because one place nearly always holds twenty weeks of a herd's need. Bare ground comes from the resident herds sharing it, not from the herd alone.

## Proposed fixes, in order

1. **Hunters weigh their country, not only their prey.** A kind's habitat suitability (`suits`) goes into how hunters choose their ground, as it already does for residents. Cheetah, lion and striped hyena then hold open country even where prey is somewhat thinner.
2. **Escape terrain pays.**
   - Rugged-adapted prey (ibex, mouflon) lose much less to hunters in rugged ground. `Niches.shelter` exists but counts for half against scent hunters. Make crags count fully for the kinds that use them.
   - Dry-adapted herds lose less in open dry country, where they see hunters coming.
3. **Herds weigh safety and water against food.** Hunters' presence and the herd's own water independence go into the hand-written scaffold's scoring. This is also something the herd network can learn.
4. **Look again at semi-desert forage.** Real semi-desert carries roughly 0.05–0.3 kg/m² of standing forage. The low end is right, but the share that is edible there may be set too low (`PALATABLE` 0.35).
5. **Ostrich decline, wolf and leopard collapses:** follow them in a long run, once fixes 1–3 are in.
