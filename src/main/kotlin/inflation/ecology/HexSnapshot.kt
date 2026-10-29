package inflation.ecology

import inflation.Profile
import inflation.store.Named
import inflation.store.Reader
import inflation.store.Restorer
import inflation.store.Writer
import inflation.world.Biome
import inflation.world.Climate
import inflation.world.Geology
import inflation.world.Grid
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Terrain
import inflation.world.World
import inflation.world.WorldConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The state of a running hex model saved to a file and loaded back into a freshly built one, so that
 * a long run can be carried on after small fixes to the code instead of started anew.
 *
 * What is saved is everything that changes as the model runs: the weather, the land and its plants
 * and water, the residents, small life and water life, the groups, the eruptions. The world itself
 * (terrain, rivers, climate normals, the hexes) is not saved: it is generated again from the seed.
 *
 * Fields marked @Transient are not saved: what the model only works out from the world (each place's
 * slope, soils and normals, the hex model's rivers, the water's depths), so that a fix to how it is worked
 * out reaches a run carried on from a save too.
 *
 * It goes by field names, not by layout: a field the code no longer has is dropped, a field the
 * saved state lacks keeps what the fresh model gave it, an array that has grown keeps the fresh model's values past the saved
 * ones, and an array that has shrunk is taken as saved. So small changes to the code still load; a save whose numbers would
 * mean something else to today's code is refused: another map, another model major version ([MODEL_MAJOR]), or other kinds
 * kept as residents or as groups.
 */
object HexSnapshot {
    private const val MAGIC = 0x48455853 // "HEXS"
    /**
     * The layout of the file. 2: what lazy fields hold is saved. 3: the order of [ORDERED]'s entries is saved. 4: the model
     * version and the kinds kept by kind are saved; older saves are refused, as nothing says what model made them. Older code
     * refuses newer saves.
     */
    private const val VERSION = 4

    /**
     * What saved state means. Raise the major version with a change after which a saved world's numbers would mean
     * something else or leave the model in a state it could not reach by running (a kind living another way, a stock
     * measured in other units, a table kept by another key): a save of another major version is refused, and the test
     * worlds must be settled again. Raise the minor version with a change that leaves saved state valid but sets the model
     * to drift from it (a growth rate retuned, a new cost): such a save loads with a warning, and whoever tests on it
     * judges whether to settle again first. Set the minor version back to 0 with a new major one.
     *
     * 1.0: onager live both ways.
     * 1.1: sowing charged its full person-days.
     * 1.2: gathering materials takes time.
     * 1.3: caching food takes time.
     * 1.4: mending first is a band way.
     * 1.5: residents count the protein in straw and in what they switch to, by weight, as herds do.
     * 1.6: residents remember days without water.
     * 1.7: bears sleep through the cold only when hungry.
     * 1.8: thirsty residents spread toward water (#210).
     * 1.9: crowding and breeding room count a kind's animals in both forms (#211).
     * 1.10: herds sicken by crowding as residents do, counted over the ground each herd grazes.
     * 1.11: fallen mast lies as winter food, the mast and root stocks from records; rooting churns the ground at its
     * recorded rate; mothers bear as their food and reserves can build their young.
     * 1.12: resident hunters take game the less readily the bigger it is past their preferred size; jackals prefer 4 kg, take 15 at most.
     * 1.15: herds count the animals hunters would kill as deaths where they choose to go, and no longer shun hunters for food.
     * 2.0: every plant-eater lives both ways: residents leave as groups where staying is clearly deadlier than a place a
     * month away, and groups settle where it is not; resident hunters, disease and crowding meet a non-migratory herd as
     * its kind's groups over HERD_UNIT times the ground.
     * 2.1: species records follow their sources (#213); a leaving group heads for the place found for it; groups form only
     * where no slope leads to safety; residents walk towards forage within a week's going (#219).
     * 2.2: residents weigh places by the hunters' kills too, resident and roaming.
     * 2.3: grass loses nourishment and protein as it goes to stem and seed (HexPlants.grassAge).
     * 2.4: crowding halves a place's appeal for residents where disease starts to bite (CROWDED), not at 20 times usual.
     * 2.5: afternoon storms over land against the observed free air: summer rain on high ground north of about 36 °N.
     * 2.6: animals eat soft lying snow for water (HexDrinkingWater.snowWater); rain on snow ices it over until fresh snow.
     * 2.7: the summer high reaches north only to about 46 °N (fronts more often in the north's summer), and air over hot
     * inland ground holds more before it rains out on slopes.
     * 2.8: residents move every second week (Tuning.residentMoveWeeks), and their animals under 1% of usual numbers at a
     * place gather into the next thin place (Tuning.residentFloor).
     * 2.9: hooves no longer take grown shrubs; they kill the seedlings of shrubs and trees on the ground they step on (HexPlants.tread).
     * 2.10: a place's room for an arriving band is how well it would be fed there by the residents' own feeding rule
     * ([HexFauna.forage]), so the foresight behind walking, band shifts and gathering into herds no longer foresees
     * starvation that those already there don't meet.
     * 2.11: the grass a plant-eater gets at under snow falls evenly from bare ground to none at its snow limit
     * ([grassUnderSnow]), in its feeding and in how it weighs a place; the wild horse's limit is 125 mm of water.
     * 2.12: residents set off as a herd, and herds settle back, by the death odds a herd weighs its places by
     * ([HexGroups.herdOddsAt]); a herd no longer keeps together until it is beyond [gatherKm] of where it formed; and the
     * place residents set off for, and a herd weighs settling against, is one it can walk to (not across the sea or a
     * river too deep to cross).
     * 2.13: a herd weighing whether to settle back weighs itself, whole, at both places, its own heads where it grazes now
     * taken out of the others' (it was crowded by itself where it stood and weighed as a band's worth alone elsewhere).
     * 2.14: a band's hunters (half its hands at the scaffold's effort on big game) search for game all the days they are out,
     * while the rest gather ([HUNTERS_SHARE]).
     * 2.15: a band weighs camps by the sim's own firewood, fire and raw-food rules ([fuelShare], [hearthShare],
     * [warmthNeed]), and a band with learned ways reads the bands about it after this week's moves.
     * 2.16: snow deeper than a glacier there can stand flows as ice to the next place down ([inflation.world.HexHydrology.iceCapMm]).
     * 2.17: one rule for how well off a band is ([wellOff], 30 days of larder as a week fed), one sickle gain
     * ([Band.sickleGain]), and caches keep by the week's weather, smoked and salted as the larder ([driedKeep]).
     * 2.18: salting is a skill a band hits on with salt to hand ([Skill.SALTING]), no longer open to any band holding salt.
     * 3.0: plant-eaters live only as residents; the groups are the hunters and people. Plant-eaters weigh a place by the
     * rate a band of them would grow at there over a year, by the rules they are born and die by ([HexFauna.growthFor]), over
     * the ground a band ranges in a day; they flow over the land as a fluid finds its level, from each place a share to the
     * neighbouring places worth more at the numbers there and here ([HexFauna.flow]), every week; and a mother bears a
     * year's young once, wherever she walks ([HexFauna.bred]), a week's share of her season by its days, and a place is
     * worth its births to movers whatever its calendar.
     * 4.0: a hex is one place, or two (its low and high ground, split at the median height of its land) where its land
     * rises more than [inflation.world.HexWorld.SPLIT_RELIEF_M]; the five fixed height bands are gone. Whatever looks to
     * another hex at a place's height takes the place there nearest in height ([inflation.world.HexWorld.placeNear]).
     * 4.1: a hunting party's search no longer stops at the places on its way: what they are too small for is spread over
     * the places about its way ([HexGroups.huntGround]).
     * 4.2: plant-eating residents remember how their kind grew on each kind of ground (country and the third of the lie
     * of the land) in each month (the seasonal memory), and a share of them take a step a week along the route to the
     * ground a month's going reaches that is best next month for its season; a place is weighed by births and deaths
     * alike over the rest of the month.
     * 4.3: the memory's route crosses only borders a week's going crosses, and as few step along it as the border is hard
     * to cross (a route over all but closed borders had walked them 1e10 km and starved them of their fat).
     * 4.4: plant-eating residents weigh places for the flow by a year of this week again (births over the birth
     * season's weeks), not the rest of the month; the memory's route still weighs a month.
     * 4.5: the memory's route leads to the ground a kind remembers growing best on next month, not the ground best
     * against its own year.
     * 4.6: residents and groups share one rule each for crowding (heads over the usual density on the ground ranged,
     * [Hazards.crowding]), for whether a place is clearly worse ([Hazards.survivalSteps]), for reach (tether and biting
     * insects), for where newcomers start, and for births (a week's share of the season, spaced by crowding: [youngDue],
     * [seasonShare]); people keep their own births. Resident hunters and the groups count game alike, by how catchable it
     * is ([HexFauna.game]), and resident hunters kill by it; beasts of prey foresee how fed they would be by the residents'
     * feeding rule.
     * 4.7: the memory learns only what the ground and its weather do to a kind (births by its food; deaths of hunger,
     * thirst, cold, snow, fire and flood), as a mean over some ten years; half the residents a week set off along its route.
     * 4.8: a hex's low and high ground are the foot and top of one slope: residents crossing into the next hex come to
     * its ground nearest in height to theirs, and gain or lose height within a hex ([HexFauna.neighbourPlaces]).
     * 4.9: hunger kills as the body's protein runs out once the fat is spent ([Hazards.hunger], [Hazards.starveDays]):
     * residents and groups count the days of it they have burnt ([HexFauna.spent], [HexGroup.spent]), a shortfall past
     * the fat and past what plant-eaters save by spending less ([Hazards.economy]) burns it, and food beyond need wins it
     * back; residents carry their fat and their burnt body when they move. Heat is booked as its own cause of death. Grass
     * buried under snow is made up with browse to at most (1 + [SWITCH]) times its usual share, as fruit and roots are. The
     * standing straw is no longer a food of its own: it is the cured grass, grazed as grass.
     * 4.10: the residents' memory tells ground apart by its country and its height (below 500, 1000, 1500, 2500 m and
     * above), weighs each place by its own ground rather than its hex's, and looks a season ahead; a save's memory by the
     * older kinds of ground is forgotten and learnt again.
     * 4.11: the residents' seasonal memory and its route are gone (it moved no animals worth the cost): the flow weighs places
     * as it did before 4.2, by what the places are worth now.
     * 4.12: a band draws its place by its will to explore ([exploreChoice]) instead of taking the best: at the scaffold's 1 as
     * its network draws while learning, and its place scores lower the keener it is.
     * 4.13: six of the bands' ways lose their caps: shelters are built up to twice as warm, caches dug with up to twice the
     * care (time against beasts and finders), the sick shunned at half the will (half the time at the default), feasting and
     * appointments as keen as the will for bands that hold the custom, and gatherers keeping near walk less.
     * 4.14: bands gather wood and good stone as far as their time and the country allow, up to what they want to carry and
     * stack, not a few kg a week: a band that keeps its camp long enough to build ([houseAfterWeeks]) gathers the timber and
     * stone for houses (it stacked them after 4 weeks before, which the caps made moot).
     * Dogs are always on (taming is no longer a switch), and a band sows at a camp it has kept long enough to build at, not
     * only at home.
     * 4.15: food feeds by its energy ([Nourishment]), one unit for every kind: bands choose foods by energy a day of handling
     * and count dry fruit, roots and seed at some 2-3 times a kg of meat; mixed feeders' plant food feeds them more and
     * their animal food less than a kg each (pure plant-eaters and hunters as before, but for fruit); people's stores hold
     * food at what it feeds. People's salt need is the sodium their meat and fish fall short of (the Yanomami's 23 mg a
     * day), not a share of plant food eaten. Four new ways choose what a band lays by ([Band.storeWill]): its store work weighs
     * each food by its will to keep it (at the scaffold's 1, as it would eat it).
     * A band's place memory keeps how richly good stone, salt, ochre and shells were to be had where it went (and as other
     * bands tell it), and its learned mover sees what it lacks remembered at a place and the way toward the best of it.
     * 4.16: protein feeds people only up to 35% of their need ([Nourishment.PROTEIN_MOST]): a band eats lean food to that
     * ceiling, then plant food and grease, and lean food beyond it feeds by its fat alone; it weighs lean food in its choice by
     * the share of its protein that went to waste the week before ([Band.leanLost], also a ways input). A band that knows
     * stone boiling renders grease from the bones it comes on ([greaseOf]), kept as a store of its own ([Store.GREASE]).
     * 4.17: a band's food efforts split its gatherers' days (splitDays, gone in 4.67): an effort above 1 meets its food more and every
     * other gatherers' food less, by the mean effort weighed by the days each would take; at every effort 1 as before.
     * 4.18: houses and palisades stand in the world ([Structure]), no band's own: any band may use those nobody else is using,
     * they weather away while nobody does, and a band building at its camp builds for those of its people the houses there do
     * not shelter. Houses a band may use draw it wherever they are (home pull), and shelter it in what it foresees of a place.
     * A band's houses in a world saved before are raised where they stood.
     * 4.19: a band plans its week's time ([BandTime.plan]): food and firewood for the week first, then the stores and each
     * chore ask for days, shared out when they come to more than what is left; each chore works in the days it was given
     * and what one leaves is free for those after it. Leisure is what the week leaves.
     * 4.20: a band's leisure goes to visits: its ties with friendly bands within a day or two's walk fade the slower the more
     * leisure it had for each of them ([VISIT_SHARE]).
     * 4.21: moving camp takes days: all a band's people walk, at its walking children's pace, held back by its weak
     * ([moveDays]); the walk comes off the week first, and a band weighing where to camp counts the days beyond its spare ones.
     * 4.22: a camp move carries food and goods in one load a walker ([CARRY_KG]): food first, goods in what room is left, the
     * rest of the goods left at the old camp; adults under their loads walk slower ([loadedKmh]); relays walk the way again
     * for each load past the first ([carryLoads]).
     * 4.23: the people's ways net sets how many loads a band carries on a move ([BandWay.CARRY_LOADS]) and how it weighs each
     * activity's ask for days against the others' when the week is short ([timeWay]); at the scaffold's 1 as in 4.22.
     * 4.24: a band weighing where to camp spreads the walk's days beyond its spare ones over the weeks it lately keeps a camp
     * ([Band.stayWeeks]), not one week: a better camp pays the walk back for as long as the band stays.
     * 4.25: a camp move takes first the goods its tools' mending needs over a stay and a year's good stone, salt and ochre
     * ([mendingKg]), then food, then the rest of its goods in the room left.
     * 4.26: the bands' ways net reads the week's camp move (its km, the days the walk takes, the load an adult carries).
     * 4.27: what bands build serves nothing till it first stands whole (houses shelter, a palisade guards, only once done; a
     * finished one then serves as far as it stands as it weathers and is mended); the ways net reads the houses and palisade
     * begun and not finished where it is. (Saved houses and palisades load as not finished.)
     * 4.28: a band's days take food by how hard their work is ([Activity.restSaves], from FAO/WHO/UNU 2004 activity ratios):
     * the week's want falls with the days its plan gives to lighter work and leaves to rest, and rises with wood fetched.
     * 4.29: house designs differ beyond their cost: a sunken one brings the ground's year-round warmth in ([earthGain]), a
     * mudbrick one wears in the rain ([MUD_EROSION]); and the bands' ways weigh their keenness on each design ([buildWay]).
     * 4.30: the food rest saves comes off what a band eats only, once its foraging has taken the days it took, not off what
     * it forages for ([restedDays]): cutting the forage aim too left bands short (10 y storm, 4.28 -6..-8.5% people).
     * 4.31: people's cold by their body heat ([BodyHeat], ISO 11079's heat balance) through a day asleep, about camp and at
     * work: night and day, wind, rain, the ground under a sleeper; shelters break the wind, keep the rain off and carry
     * bedding; shivering costs food and the debt past it kills of exposure, in place of the felt-temperature rule.
     * 4.32: fishing is practised with a fish spear or basket traps as well as nets, snaring with a bow as well as nets
     * ([Band.toolsFor]); bands keep where their food came from over a year ([Band.foodFrom]) and how fish paid
     * ([Band.fishPull]), and a band split off keeps its parent's running worths. Clothes half as warm ([CLOTHES_CLO] 1.5).
     * 4.33: the brown bear takes people a fifth as readily ([Species.takesPeople] 0.4 to 0.08; main's 4.18, merged with the
     * above).
     * 4.34: hunting, tracking and stalking are learned (and forgotten) at about a ninth of the old rate ([HUNTING_PRACTICE]).
     * 4.35: fishing is learned as quickly as snaring and gathering ([Skill.complexity] 0.5, was 1.5).
     * 4.36: misery costs work: people break off work in the cold to warm up, and hunger past their fat saps them, as far as
     *   their band's perseverance ([Band.sisu], [Sisu]) does not keep them at it; perseverance carries the heat debt instead.
     * 4.37-4.39: a mother's fraction drawn, not cut; parents feed children first in a lean week (CHILDREN_FIRST).
     * 4.40: salt hunger: a band eats meat for its sodium first, salted meat counts, and a band short of sodium foresees the
     *   salt lack at places with no salt to be had ([Band.sodiumShort], [saltFeeds]); two band inputs for it; a band short of
     *   sodium is given salt to eat at meetings by one holding more than its own people's year of it ([SALT_BODY_YEAR]).
     * 4.41: a band short of sodium heads for the nearest salt it knows of, pan or shore ([HexGroups.saltSources]).
     * 4.42: the salt pull weighs a year of salt against the weeks the walk takes; a band foresees the shelters it would have at
     *   a place it moves to ([HexGroups.shelterAt]): none where the walk takes the days it had to spare.
     * 4.43: people pick fruit at the record's rate ([BAND_FRUIT_DAYS_PER_KG], three times as long as before); shellfish are
     *   gathered off the shore as their own offer, at the Meriam rate and with no skill or tool ([SHELLFISH_DAYS_PER_KG]),
     *   and only fish caught is practice at fishing.
     * 4.44: a camp is the hex: staying, for the fouling that makes a band want to leave and for the camp weeks the sicknesses
     *   foresee, is staying in the hex (it was staying on the same ground, so the other ground of a hex looked like a fresh camp
     *   while the week kept the camp weeks going); the sick a band foresees among the bands gathered at a place are those of
     *   the whole hex, where the meetings are.
     * 4.45: a sown field is a crop of its own ([HexFood.fieldGrain], [HexFood.fieldDomestic]): it takes after the seed sown
     *   in it and is reaped before the wild stand, instead of mixing into the whole place's wild stand, which swamped it.
     * 4.46: a field sown in autumn stands through the winter and ripens the next summer (it was struck off at midwinter,
     *   so no sown field ever ripened); a field ripens as much less as it went short of water since midwinter
     *   ([HexFood.CROP_DEPLETION], FAO-56).
     * 4.47: a plot cropped year after year bears less, and mends as it rests ([HexFood.fieldSoil], Broadbalk).
     * 4.48: the wear is the worn ground's, not the whole place's ([HexFood.fieldWorn]): a field is sown on rested ground
     *   first ([HexFood.cropSoil], [HexFood.SEED_RATE]), so a plot of a few hectares no longer wears a place of square kilometres.
     * 4.49: a band aims to store, besides a month against bad weeks, the days its food fell short over the year
     *   ([Band.leanDays], from [Band.monthSupply]), its store choice a share of both ([Band.storeShare]); it was a month
     *   or two at most, so no band could lay by for a winter.
     * 4.50: a band keeps what it reaps from sown fields as seed corn and sows it first ([Band.seedCorn]); the grain it
     *   gathers from wild stands no longer mixes into the seed it sows (sown fields went from 0.75 to 0.02 ears that hold their
     *   grain within five years, as wild grain swamped the seed).
     * 4.51: a band weighs a camp by the ripe grain standing there, wild and sown, as it weighs other plant food ([campPlants]);
     *   and the crop it has sown draws it to stay by it till it is reaped ([HexGroups.cropPull], [Band.sownPlace]): before, no
     *   choice of camp saw grain at all, and villages walked away from their fields.
     * 4.52: a band brings in its own ripe crop whole as seed corn, as field work in sowing's time ([harvestCrop]), at the
     *   reaping rate of any grain ([reapDaysPerKg]); before, a crop was reaped only a week's meals and stores at a time, and
     *   most of it stood till it was shed or reaped by others (seed corn fell twentyfold in ten years).
     * 4.53: people's births are spaced by what weans their children and by carrying them ([birthSpacing]): gruel of seed
     *   ([Band.weaningFood]) shortens nursing, and a mother of a band that moves camp often spaces births till a child walks
     *   the moves; this replaces the flat bonus of settled, well-stocked bands (settledFertility).
     * 4.54: weaning gruel is of seed ground to meal ([Band.gruel] needs a grinder): before, 72-88% of weanlings were weaned on
     *   gruel in mobile and settled bands alike, so weaning food did not tell the two ways of life apart.
     * 4.55: a band's gatherers walk to the plant food and carry it home a load at a time ([GATHER_LOAD_KG]), so food
     *   further from camp, and the far side of a place they strip, takes longer a kilo; and they search for roots before
     *   they dig them, longer a kilo the thinner they grow ([ROOT_SEARCH_M2_PER_DAY]); a band's week holds its days first
     *   for the firewood and shelters as far as they avert the deaths it foresees this week and through the year after
     *   ([survivalDays]), then for the week's food and the rest of the firewood;
     *   and a band choosing its camp counts roots as its gathering would find them ([HexGroups.rootsFound]).
     * 4.56: fallen mast is a stock of its own ([HexFood.mast]), no longer counted with the bulbs and roots: a band picks it up
     *   as it picks fruit, without searching for it ([MAST_KIND]); and bulbs and roots cannot be dug from frozen ground
     *   ([HexFood.diggable], the week's air at or below [inflation.world.HexDrinkingWater.THAW_C]), for people and rooting
     *   animals alike. (A 4.55 save loads with its mast still among its roots, where it rots away within the half year.)
     * 4.57: with no ways network, bands hold the scaffold's ways every week ([Band.scaffoldWays]): before, bands of a saved
     *   world kept the ways the network it was settled under last gave them (the storm worlds: natufian-4's, at model 4.0).
     * 4.58: 4.57's reset to the scaffold's ways ran in the wrong place: its `else` bound to the band test inside the loop, so
     *   with a ways network every non-band group reset all bands to the scaffold's ways after the network set them (and waysForce
     *   with them), and without one the reset never ran. Now it runs only without a ways network, as 4.57 meant.
     * 4.59: herding is no longer for settled bands only: any band may take young in the hunt to keep, and learn herding;
     *   its herders' days come out of field work's time ([herdDays], about 4,000 kg of stock a herder), held before
     *   the week's food, and none on the days the herd walks with the band to a new camp; what they cannot
     *   keep it culls, in place of a flat cap of two head a person, and it eats what the herd grows to beyond the stock its
     *   herders keep in a usual week (a year's mean), and eats down a herd whose increase
     *   brings a herder less than a day's foraging brings it ([herdPays]); a band choosing its camp weighs what poor forage
     *   there would cost its herd ([HexGroups.herdLoss]); villages started by t.startSettled start with a herd, and
     *   t.startHerders starts a share of mobile bands with one; kept herds no longer lose a share for each hex a move takes
     *   them (HERD_MOVE_LOSS, no source).
     * 4.60: only the trees that bear food bear mast and fruit, by kind of country ([HexFood.FOOD_TREES]: none from spruce, fir
     *   or larch), and the trees' crop swings from year to year, the same over all the land for each group of trees but apart
     *   between them ([inflation.world.MastTrees], [inflation.world.Weather.mastCrop]).
     * 4.61: bands burn off dry grass where any fire would take ([HexFires.canBurn]); their own stricter test (soil under 40 mm,
     *   forage under 0.4 green, herbs over 0.3) was not met at any camp in 5 years on storm-1, so the burning-off way was never read.
     *   Ways that stopped at the scaffold's 1 go on above it as more of the same for ever less ([shareOfWill], [moreOfWill]): a
     *   bigger night fire (wood as the will, guarding less for each log more), giving beyond the lack and beyond a tenth of the
     *   larder, repaying more than is owed, plotting raids on less hunger; drying all the fresh meat and fish and holding back
     *   all storing to mend stay all at 1, and above it go on as drying for longer (a kilo taking that much more time and
     *   keeping that much better: [Band.dryDepth]) and mending first ever sooner ([Band.mendBelow]).
     *   Standing grass and scrub hide small game from those who hunt it ([smallSeen], [HexPlants.groundCover]): burnt and
     *   grazed ground shows more of it.
     * 4.62: the loss to beasts of prey is split between night at camp ([NIGHT_STALKED]) and day at work ([stalkGuard]): by night
     *   the fire, dogs, a night watch (a new way, [BandWay.NIGHT_WATCH]: hours awake by turns, paid for in the next day's work,
     *   [watchPace]), house walls ([HOUSE_GUARD], as they hold: [wallGuard]) and a palisade ([PALISADE_GUARD]) keep beasts off; by
     *   day only dogs. Scouts no longer guard the camp as lookouts: they keep beasts off by where they find them. A band counts
     *   its walls in what a house is worth to it and in choosing its camp. Beasts take a tenth as many at most ([STALKED]),
     *   as foragers' records have it, and the nominal check counts those taken. (People net 5: a new way output.)
     * 4.63: a band weighs its herd against what a day's foraging brings in its leanest month ([herdPays], [Band.forageReturn]
     *   month by month), since a herd is food kept alive till then; and its herd's mothers give milk after the birth season
     *   ([milkKg], [Nourishment.MILK]): before, meat alone never paid a herder anywhere, and every herd was eaten in a year.
     * 4.64: a band eats its herd's yearly increase week by week, at what it is fed ([herdIncrease]), and keeps it where the days
     *   nobody asked for would herd it too; before, it ate all beyond a year's mean of what its herders kept, and as the herd
     *   dwindled outside the birth season that mean followed it down, so even herds that paid shrank to nothing in a few years.
     * 4.65: bands search for fruit and mast as they do for roots, the thinner the longer a kilo ([PLANT_SIGHT_M2_PER_DAY]),
     *   and must pound and leach the bitter share of the mast before they eat it ([MAST_BITTER], [MAST_LEACH_DAYS_PER_KG]),
     *   which only bands that know leaching and have water by them can, and a band choosing its camp counts the mast at the
     *   share of its days that is gathering it ([MAST_GATHERED]); grass seed and cereal grain are searched for as fruit is,
     *   and wild grasses' small seeds take [SMALL_SEED_SLOWER] times as long a kilo as wild cereal; the census counts bands' plant food by what it came from
     *   ([PlantFrom]).
     * 4.66: a band's effort on each food is e^x of its ways network's output, with no most; before, 2/(1+e^-x) held every
     *   effort under twice the scaffold's, so no network could put more than that into fish or small game (people net 6).
     * 4.67: a band's food efforts weigh each food in its gatherers' ranking ([offer]) instead of how often they meet it:
     *   a food ranked above what it pays is taken up before better ones, at its true yield, so the net's choice costs food;
     *   before, an effort only scaled a food's rate, which could never bring a food the ranking left out into the diet (people net 7).
     * 4.68: mast a band lays by keeps as seed does ([Store.SEED], 0.5% lost a week); before, it went in with roots and lost
     *   3% a week, four fifths in a year, so no band could carry a good mast year over a poor one. Roots and bulbs in the
     *   ground are counted by their energy, as the root search was fitted ([HexFood.ROOTS_SHARE] 0.01 to 0.03).
     * 4.69: a herder keeps 200 goats, fewer of bigger beasts as their weight to the half power ([headsAHerder]); before, 4,000 kg
     *   on the hoof of any kind (a hundred goats, from memory), which made a herder's day bring half what shepherds' does.
     *   A slaughtered herd beast gives its hides, bone and sinew as a kill does ([Band.skin]); before, they went to the
     *   scavengers. Its herders' days are held after the week's food and before the other asks ([BandTime.plan]); before,
     *   with the fire and shelters, so a band that shrank kept its whole herd, which took its firewood and food till it died.
     * 4.70: a band forages only for what its herds will not feed it that week ([herdFoodWeek]: their increase and milk);
     *   before, it planned to forage its whole week's food, so in its lean months foraging took every day and the herd
     *   was culled for want of herders.
     * 4.71: young caught in the hunt and kept come [RAISED_TAME] tame, as raised by hand, or as tame as their kind's worst
     *   domestication criterion where that is less; before, they came as tame as the
     *   band's herd of their kind, wild where it had none, so they ran off, bred little and were eaten within weeks.
     *   The odds of keeping one go by its kind's worst domestication criterion, not their mean.
     * 4.72: weirs ([Built.WEIR], [buildWeir]): a band builds a fence of stakes and traps across the river at its camp where
     *   fish from one would rank in among its gatherers' foods ([Band.weirPull]); its fish take the traps' watch over what
     *   they take and a little landing and gutting a kilo ([weirDays]), so they pay only where many fish pass (a spawning
     *   run). Before, every kilo of fish took the same handling wherever and whenever it was caught. Weirs wear in the
     *   stream; the mover and the people net's fish input see their catch.
     * 4.73: a band puts up its camp's shelters as soon as its week is planned, before foraging can overrun into their days
     *   ([putUpShelter]); built after the foraging and the other chores, they were the first a week's overrun took.
     * 4.74: a river's spring run is as big as its spawning grounds hold ([HexWater.RUN_KG_PER_KM2], fitted to the Rhine's
     *   salmon) as far as the sea off its mouth can give; before, it was a twentieth of the fish off the mouth whatever the
     *   river, some 600 t a year over the whole map.
     * 4.75: a weir holds back a share of the run's fish passing up its hex bound for the river above ([HexWater.penned]), and
     *   the hexes above get the rest; before, it took a share of its own hex's part of the run, as if no fish went by it.
     * 4.76: a band's marks ([Tradition.mark]: burial, leaving the dead, marrying out, sparing young game, feasts) go the way most
     *   of the bands it has met lately go ([conformist], [Band.heard]), not the way of the band doing better; know-how is still
     *   copied from the better off. Founders' lineages are drawn per 150 km block ([lineageAt]) instead of repeating every 600 km.
     * 4.77: a band keeps its herd's increase, females with it, while its leanest month would still herd the herd grown,
     *   and culls above that, a month's share of the excess a week ([Band.freeShare]: the share of its days its fire,
     *   shelters and food leave, month by month); before, it kept the increase only where that week's spare days would
     *   herd it, so a herd could only hold or shrink. A herd that kept its increase to grow feeds the band only its milk
     *   ([Band.herdGrew]), and the band forages for the rest.
     * 4.78: the spring run's fish are a food of their own for a band on the river ([RUN_KIND]): a kilo takes the wait for fish
     *   to come within a fisher's reach ([HexWater.runWithinReach], [HexWater.RUN_REACH_M]) and its landing, so a run crowding a
     *   narrow river pays many times what a thin one does; before, they were one with the river's own fish at 0.3 days a kilo.
     * 4.79: a light hut (a tenth of a person-day each) keeps off the wind and rain and holds the bedding but no warmth; its
     *   thatching, some 2 person-days each ([THATCH_DAYS]), holds the warmth in ([SHELTER_C]), and the week's survival
     *   pass thatches as far as it averts deaths, whatever the band's ways aim at ([shelterWork], [Band.shelterAim]);
     *   before, the tenth of a day's hut gave the warmth too. The survival pass counts the food its days leave unforaged
     *   (before, it reckoned with all the week's food got, so the warmth's days were free).
     * 4.80: a herd begun from young caught in the hunt is kept as pets till they have grown ([Band.herdPet],
     *   [Species.yearsToMaturity]): kept whole and not weighed as food; before, it was weighed at once, and young of kinds
     *   that pay only when tame were eaten within the week.
     * 4.81: kept animals still wild stray at a fifth of the rate ([HERD_RUN_OFF] 0.1, was 0.5 with no source): a herd as tame
     *   as one at world start loses 2% a year, as herded goats in central Mali lost to straying and predators.
     * 4.82: kept animals stray as the square of their wildness ([HERD_RUN_OFF] 0.5 × (1 − tameness)²; still 2% a year at
     *   tameness 0.8): a 0.6-tame herd loses 8% a year, a 0.2-tame one 32%; before, in a straight line, half-wild ostriches
     *   lost little enough to be herded on foot.
     * 4.83: a fire warms by the wood it burns ([BodyHeat.fireC]): a kilo a person a day a degree in the open, 0.67 in a
     *   light hut, half a kilo in a hut thatched whole, and a house's air 0.15 ([FIRE_HOUSE_KG_C]); a whole cooking fire
     *   burns a kilo ([HEARTH_WOOD]), and the survival pass burns more as far as it averts deaths, up to the days a band
     *   gives firewood at most ([fuelMost]). The survival pass reckons the year after under the shelter it weighs through
     *   the weeks the band expects to stay on ([Band.stayWeeks], [yearAhead]). Before, a kilo a person a day kept those
     *   about it 10 °C warmer, and a house's air 10 °C more, whatever the cold or the cover, and a shelter was weighed by
     *   its first week alone.
     * 4.84: young raised by hand come [RAISED_TAME] tame whatever their kind (before, no tamer than its worst criterion, so
     *   raised kids and lambs stayed 0.6 and never paid); a kind's ease on foot (the worse of panic and social structure)
     *   scales the straying instead ([straying], [HERD_EASE] 0.6): at tameness 0.8 goats, sheep and boar lose 2% a year,
     *   aurochs 11%, ostrich 18%, red deer 27%.
     * 4.85: all a band does toward another goes by its [Regard] of it. Setting out to raid a home and robbing a cache now
     *   take the same wariness as a raid at a meeting ([Regard.fightChance]: less among kin and those who understand each
     *   other, more on strangers and those of other customs); a band sets out by its regard of the band it is warier of, and
     *   of homes about as safe falls on that one before the nearest. Each band believes what it is told by its own trust of
     *   the other; before, both by the first band's.
     * 4.86: every lineage keeps a resident kind as its totem ([totemAt], [Band.totem]), which its hunters leave alone unless
     *   the band went hungry the week before; a band known to have eaten of it ([TOTEM_SHAME_YEARS]) is not seen to keep it.
     *   A totem passes to offshoots, goes the way most bands about go as a mark does, and now and then is given up or taken
     *   up; it is one of the customs bands count each other apart by ([CUSTOMS], [Regard.customsApart]).
     * 4.87: the survival pass reckons a shelter through the weeks a band expects to stay on as one that decides each week
     *   whether to move: its usual stay less this week, or the weeks it has kept the camp where those are more
     *   ([Band.stayWeeks], [survivalDays]). Before, its usual stay less the weeks it had kept the camp, so a band that had
     *   outstayed its usual stay weighed a shelter by this week alone.
     */
    const val MODEL_MAJOR = 4
    const val MODEL_MINOR = 87
    /**
     * Enums whose entries index saved arrays by position (a band's skills, tools, goods, stores, illnesses, customs, plant
     * lore, ways): a save is refused where the code has them in another order or without one it saved, since the
     * numbers would land on the wrong entries; entries added at the end keep the fresh model's values.
     */
    internal val ORDERED: List<Class<out Enum<*>>> = listOf(Skill::class.java, Tool::class.java, Material::class.java, Store::class.java,
        Illness::class.java, Tradition::class.java, Biome::class.java, BandWay::class.java, inflation.world.MastTrees::class.java)

    /** Writes the state of [sim] to [file]. */
    fun save(sim: HexSim, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(object : GZIPOutputStream(tmp.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } },
            1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeLong(sim.cfg.seed); out.writeInt(sim.land.n); out.writeLong(sim.weather.date.absoluteDay)
            out.writeInt(ORDERED.size)
            for (e in ORDERED) { out.writeUTF(e.name); out.writeInt(e.enumConstants.size); for (c in e.enumConstants) out.writeUTF(c.name) }
            out.writeInt(MODEL_MAJOR); out.writeInt(MODEL_MINOR)
            val rs = rosters(sim)
            out.writeInt(rs.size)
            for ((name, kinds) in rs) { out.writeUTF(name); out.writeInt(kinds.size); for (k in kinds) out.writeUTF(k) }
            val w = Writer(out, ::saved, lazies = false, SPECIES)
            for ((name, root) in roots(sim)) { out.writeUTF(name); w.value(root) }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) error("could not write $file")
    }

    /** Loads the state in [file] into [sim], which must be built over the same world (seed and size). Returns the day it was saved on. */
    @IgnorableReturnValue fun load(sim: HexSim, file: File): Long {
        DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream(), 1 shl 16), 1 shl 16)).use { inp ->
            require(inp.readInt() == MAGIC) { "$file is not a saved hex world" }
            val version = inp.readInt()
            require(version <= VERSION) { "$file was saved by newer code (file version $version)" }
            require(version == VERSION) { "$file was saved before saves carried the model version that made them, so it may hold numbers today's model reads otherwise: settle it again" }
            val seed = inp.readLong(); val n = inp.readInt(); val day = inp.readLong()
            require(seed == sim.cfg.seed && n == sim.land.n) {
                "$file is of another world (seed $seed, $n places; this one seed ${sim.cfg.seed}, ${sim.land.n} places)"
            }
            repeat(inp.readInt()) {
                val name = inp.readUTF(); val saved = List(inp.readInt()) { inp.readUTF() }
                orderProblem(name, saved)?.let { throw IllegalArgumentException("$file: $it") }
            }
            val major = inp.readInt(); val minor = inp.readInt()
            modelProblem(major)?.let { throw IllegalArgumentException("$file: $it") }
            val code = rosters(sim).toMap()
            repeat(inp.readInt()) {
                val name = inp.readUTF(); val saved = List(inp.readInt()) { inp.readUTF() }
                rosterProblem(name, saved, code[name].orEmpty())?.let { throw IllegalArgumentException("$file: $it") }
            }
            if (minor != MODEL_MINOR) println("WARNING: $file was saved by model $major.$minor, this is $MODEL_MAJOR.$MODEL_MINOR: it loads, but the model will drift from it; settle it again if that matters to the test.")
            val r = Reader(inp, SPECIES)
            val byName = roots(sim).toMap()
            val restore = Restorer()
            repeat(byName.size) {
                val name = inp.readUTF()
                val node = r.node()
                byName[name]?.let { restore.into(it, node) }
            }
            if (restore.dropped.isNotEmpty()) System.err.println("$file: the code no longer has, and so dropped: ${restore.dropped.joinToString(", ")}")
            return day
        }
    }

    /**
     * Why entries [saved] in this order for the enum class [name] would load wrongly (null if they load right): the code
     * must have them in the same order, perhaps with more after.
     */
    internal fun orderProblem(name: String, saved: List<String>): String? {
        val code = ORDERED.firstOrNull { it.name == name }?.enumConstants?.map { it.name } ?: return null
        if (code.size >= saved.size && code.subList(0, saved.size) == saved) return null
        return "${name.substringAfterLast('.')} is saved in another order than the code has, so its values would land on the wrong entries: saved $saved, code $code"
    }

    /** Why a save made by model major version [major] would load wrongly (null if it loads). */
    internal fun modelProblem(major: Int): String? =
        if (major == MODEL_MAJOR) null else "it was saved by model major version $major, this is $MODEL_MAJOR: its numbers mean something else to today's model; settle it again"

    /**
     * Why the kinds [saved] for the tables kept by kind under [name] would load wrongly against the code's [code] (null if
     * they load right): the tables are restored by position, and a kind added, dropped or moved would start from numbers that
     * are not its own.
     */
    internal fun rosterProblem(name: String, saved: List<String>, code: List<String>): String? =
        if (saved == code) null else "it keeps other kinds as $name than the code does, so their numbers would land on the wrong kinds or start from none: saved $saved, code $code"

    /**
     * The kinds each part keeps its tables by, in order: the residents and those living as groups. Left out are the kinds the
     * config moves for a comparison after a load (people=false), which change the code's lists but not what a
     * save holds.
     */
    internal fun rosters(sim: HexSim): List<Pair<String, List<String>>> {
        fun names(kinds: List<Species>) = kinds.filter { !it.human }.map { it.name }
        return listOf("residents" to names(sim.fauna.kinds), "groups" to names(sim.groups.kinds))
    }

    /** Kinds are saved by name: the code defines them. */
    private val SPECIES = Named(Species::class.java, { it.name }, { n -> Catalogue.all.firstOrNull { it.name == n } })

    internal fun roots(sim: HexSim) = listOf("weather" to sim.weather, "land" to sim.land, "fauna" to sim.fauna, "groups" to sim.groups,
        "eruptions" to sim.eruptions)

    // ---- what is saved

    /** The world's fixed structure, regenerated from the seed; and what is only worked out from the rest. */
    private val FIXED = setOf(World::class.java, HexMap::class.java, HexWorld::class.java, Grid::class.java, WorldConfig::class.java,
        Climate::class.java, Terrain::class.java, Geology::class.java, Profile::class.java)

    internal fun saved(c: Class<*>): Boolean {
        val n = c.name
        if (c.isSynthetic || n.contains("\$\$Lambda") || n.contains("\$Lambda")) return false
        if (c in FIXED) return false
        if (HexPolicy::class.java.isAssignableFrom(c)) return false
        return n.startsWith("inflation.") || n.startsWith("kotlin.random.")
    }
}
