# Culture review: regard, customs, rites and meetings

A third review, after [consolidation-review.md](consolidation-review.md) (movement) and [life-review.md](life-review.md) (eating, deaths, skills, the ways networks). This one reads the bands' social side: how one band regards another, what changes that regard, how customs steer what a band does, and the rites and meetings where bands come together. It looks for the same kinds of problems:

- a rule decided in more than one place;
- the same quantity reckoned several ways;
- a mechanism that keeps a rule of its own where a shared one exists.

This side is in better shape than the other two were:

- **`Regard`** (HexCulture) is the one place for how a band sees another: understanding, tie, grudge, kinship, customs apart, and from them the chance of a fight, trust, marriage and taking up a meeting place. Nearly every choice that turns on another band reads it.
- **Splits and joins** carry every band field by a declared rule (`CARRY`, checked by BandCarryTest).
- **Rites** are walked to by the ground, and their days come from that walk.

The frays are at the edges: the writes that change regard, two customs read two ways, and a few reads that go around `Regard`.

## 1. Harm and amends each have their own formula

Reading regard goes through `Regard`, but changing it does not. A grudge rises or falls, and a tie is cut or mended, in seven places, each with its own arithmetic:

| Where | Grudge | Tie |
|---|---|---|
| a raid fought, the raided (`PeopleRaids`) | `+GRUDGE` | `−TIE_RAID` |
| a raid fought, the raiders | `+RAIDER_RESENT × GRUDGE`, only if they lost people | `−RAIDER_RESENT × TIE_RAID`, always |
| the raid's helpers | `+share × GRUDGE` | `−share × TIE_RAID` |
| a band that slipped away from a raid | `+0.5 × GRUDGE` | `−0.5 × TIE_RAID` |
| a cache robbed (`PeopleCamp`) | `+0.5 × GRUDGE` | `−0.5 × TIE_RAID` |
| a sacrilege at a rite (`Rites`) | `+held × GRUDGE` | `−held × TIE_RAID` |
| a marriage (`CultureMeetings`) | `× 0.5`, both ways | (by the meeting) |
| food from kin (`visitKin`) | `× 0.5` | a favour owed, through `helped` |

The wrongs pair a grudge with a cut tie by the same weight, except the raiders: their tie to the raided is always cut, but their grudge rises only if they lost people. None of these is wrong taken alone, but the rule "a wrong done raises the grudge against their people and cuts the tie to that band, by one weight" is written out six times, and "amends halve the grudge" twice.

**Fix:** two functions on `Band`, the one rule for each:
- `wronged(by: HexGroup, weight: Float)`: grudge `+weight × GRUDGE` against their people and tie `−weight × TIE_RAID` to that band;
- `amends(to: Band, share: Float)`: the grudge cut by that share.

Each place calls them with its weight. The raiders' case is the one that differs: their tie is cut whether or not they lost anyone. Either that stays as a deliberate exception, or the raiders' resentment becomes one `wronged` call when they lost people. That is a choice to make while fixing. Everything else is unchanged by the move, so only that choice could need an A/B.

## 2. Two customs with a way are read two ways

A band acts on a custom by `byCustom(holds, will)`: as keenly as its will, or only as far as its will goes above the default where it does not hold the custom. Customs with no way of their own (hygiene, medicine, sky lore, oral tradition, leaving the dead, the taboo on young game, exogamy) are rightly plain switches.

Two customs do have a way, and are read both ways:

- **Feasts** (`BandWay.FEASTING`, `feastWill`) go through `byCustom` in 7 places. They are a hard switch in 4 more: how big a roaming band grows before it splits (`mobileMost`, `FEAST_SIZE`), how easily a settled band lives together (`settledEase`, `EASE_FEASTS`), and the planner's pull toward gatherings (`feastDraw`, and a census tally beside it in `PlanBands`). So a band keen to feast without the custom feasts at meetings, but feasting never holds it together and its plans never draw it to others.
- **Sharing** (`BandWay.GIVING`, `giveWill`) is a hard switch in 4 places: kin's gifts are doubled (`CultureMeetings`, and the will is then applied on top), the stores' aim (`SHARING_STORE`), unevenness within the band (`UNEVEN_LEVEL_SHARING`), and the demand-sharing at a meeting (`HexMeetings`).

**Fix, as read closely:** most of these are right as they are, by one of two patterns.
- **The custom sets the norm and the will scales around it.** Sharing's four reads double the gift, halve the store aim and raise the hunger line before giving; the giving will then scales the gift (`shareOfWill`). Running them through `byCustom` too would count the will twice.
- **The custom's own effect on a people.** How big a roaming band grows (`FEAST_SIZE`) and how easily a settled one lives together (`EASE_FEASTS`) come of holding to feasts, not of how keenly a band feasts this week.

The one real mismatch is the planner's pull toward others to feast them (`feastDraw`): a band keen to feast without the custom feasts at meetings and on big kills, yet was never drawn toward others to feast. `Band.feastKeen` is now the one keenness every feast read uses, and the draw scales by it (1 at the custom's default). This changes results, so it gets an A/B.

## 3. A few reads go around Regard

- **Friends by three tests.** `Regard.friend` is `tie > FRIEND_TIE`. The meetings' greeting between friends (`HexMeetings.settle`) tests `tie >= FRIEND_TIE` both ways, and the ornaments given test `tie < FRIEND_TIE` for not a friend. So a tie of exactly `FRIEND_TIE` is a friend at the greeting and for ornaments, and not one for `Regard`. Fix: all three read `Regard.friend`.
- **The nearest of most kinship, twice.** A small band joining kin (`mergeAndPair`) and a band driven from its home seeking refuge (`PeopleSpread`) both weigh a host by `kinship / (1 + going km / hexKm)`. Both go by the ground, which is right, but the rule is written twice. Fix: one function for it.

Results should be unchanged except for the friends at exactly `FRIEND_TIE`, which is vanishingly rare. A line-for-line check covers it.

## Order

1. Item 3 first: it should leave results unchanged and is checked line for line.
2. Then items 1 and 2 in one A/B, both small changes to how bands treat each other.
