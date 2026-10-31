package inflation.ecology

import inflation.EnumFloats

/**
 * What can kill a resident animal in a week, as the shares of them it takes: hunger, cold, heat, thirst; the grown's fire,
 * flood and snow (`_A`) and the young's (`_Y`); the crowding (the pile-up of animals, not a death by itself); the hunters'
 * take of the grown; and disease in its two parts, the crowd's ([SICK]) and a raging outbreak's ([OUTBREAK]), which together
 * are [disease]. Kept per hazard in an [EnumFloats], so a share cannot be read as another's.
 */
enum class Danger { HUNGER, COLD, THIRST, FIRE_A, FLOOD_A, SNOW_A, FIRE_Y, FLOOD_Y, SNOW_Y, CROWD, HUNTED, OUTBREAK, SICK, HEAT, STRIFE, GUILD_A, GUILD_Y }

/** All disease deaths: the crowd's and an outbreak's. (A sum, not a slot of its own, so adding it to its parts is impossible.) */
fun EnumFloats<Danger>.disease() = this[Danger.SICK] + this[Danger.OUTBREAK]

/** What [HexFauna.growthParts] reports: the young born to a grown one, the shares of the grown and the young that die, how well fed and how well met their need for protein. */
enum class Growth { BORN, GROWN_DIE, YOUNG_DIE, FED, PROTEIN }
