# The many sides of a herd's size

Each herd has a herd-size way (`AnimalWay.HERD_SIZE` in `HexAnimalWays.kt`), set by the herds' ways network rather than the movement network. Many eyes and confusion cut the catch (`BIG_HERD_EYES`), the young are sheltered (`BIG_HERD_SHELTERS`) and big herds move slower (`BIG_HERD_SLOW`), all in `HexGroupRules.kt`.

Olli's point: a big herd cannot hide in a 10 km hex, a small one can; but a big herd shelters its young and old inside. Size should be a real trade-off, so that splitting and joining can be learned.

**For being big:**
- **Many eyes:** hunters are seen sooner, so fewer stalks get within reach.
- **Dilution:** a fed hunter takes one or two, whatever the herd's size.
- **Confusion:** a hunter has trouble picking out one animal from a milling crowd, so each attempt is less likely to succeed.
- **The selfish herd:** young, old and weak keep to the middle, so a hunter takes them less often in a big herd than in a small one.
- **Shared knowledge:** a big herd has more old animals that remember routes and water (the "many wrongs" principle).

**Against being big:**
- **Seen from afar:** a big herd cannot hide, so the chance of being found grows with its size. A small herd can hide in cover, and a lone animal hides best.
- **Food:** a big herd strips its range sooner, has to range wider and move more often, and walks further.
- **Crowding:** disease and parasites, and queues at water.
- **Slowness:** a big herd moves more slowly and turns less readily.

**How it fits:**
- **Being found:** meetings grow with the herd's usual bands. Cover gives hiding to small herds only.
- **Killing:** each attempt is less likely to succeed in big herds (confusion and many eyes). Young and old are taken less often in big herds.
- **Food, crowding and slowness:** grazing over the range and disease from crowding apply to every herd. Walking pace slows a little with size.
- **Choices:** the herd-size way is a learned choice. Each herd's own leaning to a size gives the training varied sizes to learn from.
