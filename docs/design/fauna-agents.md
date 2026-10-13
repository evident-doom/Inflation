# Resident prey, migratory herds and hunters

People can only learn useful habits if the animals around them behave in regular, readable ways:
where the deer are thick, when the gazelles pass, which valley a wolf pack holds. Weekly herd
movement that looked random gave them nothing to learn, so the fauna is split by how each kind
really lives.

## Resident plant-eaters

Roe, red and fallow deer, boar, ibex, mouflon, aurochs, elk, hippo and bears live out their lives
in small home ranges. They behave like a density field over the land rather than wandering herds:

- A herd stays where it is. It shifts a short way (up to three ranges) only when hunger, thirst,
  cold or heat press it.
- With the seasons it moves only within its own country, at most five ranges: up the mountain in
  summer, down to the valley in winter.
- Frightened, it hides where it is; small animals in cover are hard for hunters that do not stalk.
- It spreads into empty ground only through young herds, which leave for the least crowded side
  within a few ranges and settle there.

In the code these are still groups (so hunting, counts and the viewer work unchanged), but they
are fixed in place like cells of a field. Weekly movement fell from about 30% of herds leaving
their range to 4–9%, and on test worlds resident deer, boar, ibex and mouflon ended 50–130% higher
over 6 years, since they no longer wander into hunters or starve on the march.

## Migratory herds

Gazelle, reindeer, wild horse, onager, oryx, ostrich, hartebeest and dromedary follow the seasons
over long distances. Each kind has a small network that scores its candidate moves (the same
features every animal sees, plus staying, keeping its heading, weeks in one place, intake and
whether it has settled). The networks start as copies of the hand-written behaviour.

## Hunters

Wolves, lions, leopards, hyenas, jackals and cheetahs hold territories: they keep to their
ground unless starving, fight harder on it, work it in turn (a spot hunted for weeks has wary
game), and mark it with scent that fades over about two months. Rivals keep off fresh marks
unless hungry; a dispersing young pack settles only where it smells none, and dies more often
until it does. Each kind has a network like the herds'.

## Learning together

`fauna-evolve` evolves the migratory herds' and hunters' networks together by evolution
strategies. In every world each evolving species' groups are dealt that species' variants, so one
run scores all species at once and each adapts to the others as they change. Fitness is
animal-weeks per animal at the start, over three years: survival and young, with no reward for
any particular way of living. Fresh 600 km worlds every generation keep the networks from fitting
one map.

## People on top

Once the animals settle, bands are copied from their hand-written behaviour on the evolved
animals (`band-imitate --fauna DIR`) and evolved there (`band-evolve --fauna DIR`). The viewer
shows the evolved animals with `--fauna DIR`.
