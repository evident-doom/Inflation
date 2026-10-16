# Residents against packs and herds (2026-10-15)

Animals live in two models:

- **Residents** are numbers per place, worked out in `HexFauna`. They are cheap to compute.
- **Groups** are agents that choose where to go: herds, packs and bands, worked out in `HexGroups`, `HexLifeAndDeath` and `HexMeetings`.

Olli asked that the two not differ much. He also set a default: a kind that lives in almost every habitat and does not migrate is a resident, and packs are kept only where acting as an agent is worth the compute.

This audit compares every mechanism in the two models. File and line references are as of commit c840d1f.

## Where the two models differ most

1. **Diet.**
   - Residents eat fixed shares of each food. A channel that comes up short stays short. Switching happens only between plant foods, and never between plants and animal food or between small prey and carrion (`HexFauna` 345–409).
   - Groups take the most rewarding foods first (Charnov) and can fill their whole need from any one food (`HexMeetings` 221–245).
2. **No killing for residents.**
   - A resident's `meat` share is quietly added to its small-prey request (`HexFauna` 400). No resident ever kills another resident, its young, or a herd.
   - Groups kill residents, the residents' young and herds (`HexMeetings` 176–276, 649–706).
3. **Hunger deaths.**
   - Residents: `(0.6 − fed) × 0.08` a week (`HexFauna` 468).
   - Groups: `(0.5 − fed) × 0.3` (`HexLifeAndDeath` 345). That is about four times steeper, from a lower threshold.
4. **Heat and thirst.** These kill groups only. For residents, water only scales how much food they can reach (`HexFauna` 227–237; `HexLifeAndDeath` 349, 383–391).
5. **Births.**
   - Residents use `youngPerFemale` spread over the season.
   - Packs use `packLitter`, once a year, and only with a home ground (`HexFauna` 462; `HexLifeAndDeath` 332–338).
6. **Crowding sickness.**
   - Residents reckon the usual density by Damuth's rule over their own place.
   - Herds use a 0.1/km² fallback × `MIGRANTS_GATHER` over the hex.
   - Packs reckon over seven hexes (`HexFauna` 55, 474; `HexLifeAndDeath` 393–408).
7. **Eagles and crocodiles.** They take every resident's young but no carnivore group's (`HexFauna` 483–486; `HexLifeAndDeath` 415, 419).
8. **Fat.** Residents lay on fat only from spare plant food, so a mostly meat-eating resident can never rebuild its reserves (`HexFauna` 371–377).
9. **Only in one model.**
   - Residents only: hibernation, trampling, rooting and seed spread.
   - Groups only: memory of places, walking cost, territory, rivals, prey fighting back, splits and merges, and drowning on sea crossings (residents do cross straits, but without drowning).

## Why resident jackals starved at 60%

A jackal's diet is meat 0.1, small prey 0.5, carrion 0.25 and fruit 0.15. As a resident:

- **Small prey and meat (0.6 of its need):** always met. Rodents, hares and birds are far more plentiful than jackals need.
- **Carrion (0.25):** nearly empty. Vultures take the residents' carcasses first, and it rots.
- **Fruit (0.15):** there for only part of the year.

Nothing is allowed to make up one channel's shortfall from another. So `fed` sits at small prey + meat = 0.60 at any density. Fat drains and never refills, and they die of hunger.

As a pack, the same jackal fills its whole need from whatever pays best, including the young of deer and gazelle.

## Proposed changes to bring them together

Each change applies one rule to both models.

1. **One diet rule.** A resident's shortfall in one food is made up from the others, across plant and animal food, up to its whole need. This uses the same ranking groups use: the most rewarding foods first.
2. **A meat channel for residents.** A resident hunter takes residents and their young by the same catchability groups use (prey size, density, cover). Its kills come off the prey's numbers, and some prey fight back.
3. **Fat from any food**, for both models.
4. **One hunger rule**, one cold-and-heat rule, and thirst deaths for residents too. Thirst would be based on distance to water against what a kind can go without.
5. **One crowding-sickness rule.** The usual density comes from the catalogue or Damuth's rule, reckoned over the same area for both models.
6. **One birth rule.** A litter size and litters a year for every kind. `packLitter` becomes the same number expressed per pair.
7. **Eagles and crocodiles** take the young of any small enough kind, whichever model carries it.

## Which kinds should stay as agents

By the rule "lives almost everywhere and does not migrate → resident":

- **Residents:** golden jackal and lynx (on small home ranges). Striped hyena and leopard are candidates too: they are thin on the ground and widespread.
- **Agents:**
  - wolf, lion and cheetah, which range widely and hunt the migrating herds;
  - the migrating herds themselves;
  - people.

Moving the jackals alone removes about 6,900 of the roughly 8,000 hunter groups on the wide map, and with them most of the hunters' share of the weekly planning.
