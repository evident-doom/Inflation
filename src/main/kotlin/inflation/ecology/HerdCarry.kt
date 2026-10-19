package inflation.ecology

/** How a herd's field is set when residents gather into a new herd ([HexGroups.gather]). */
enum class OnGather {
    /** The head-weighted mean of the residents that gathered. */
    MIXED,
    /** Summed over the residents that gathered. */
    SUMMED,
    /** From the place the most of them came from. */
    FROM_PLACE,
    /** As any new group starts ([HexGroups.spawn], [HexGroups.inheritSeasons]): nothing of the residents' carries. */
    NEW,
}

/** How a herd's field fares when residents join it ([HexGroups.scatter]'s join). */
enum class OnJoinHerd {
    /** Mixed with the joiners', weighted by heads. */
    MIXED,
    /** The joiners added. */
    SUMMED,
    /** The herd's own: the joiners' is lost. */
    KEPT,
}

/** How a herd's field fares when its animals settle back among the residents ([HexGroups.scatter]'s spread). */
enum class OnSpread {
    /** Mixed into the residents' where they settle, weighted by heads. */
    MIXED,
    /** Set on the residents only where none of its kind were. */
    WHERE_NONE,
    /** Added to the residents. */
    SUMMED,
    /** Lost: residents keep nothing like it. */
    LOST,
}

/**
 * Each field of a [HexGroup] and how it fares as animals change form: gathering into a herd, joining one, and settling
 * back among the residents. Written as the code in Gathering.kt does it, so that a field a change of form drops is
 * dropped by a rule anyone can read here, not by being left out; a new field needs a line here (see HerdCarryTest).
 */
internal fun herdCarryRules(): Map<String, Triple<OnGather, OnJoinHerd, OnSpread>> {
    val r = LinkedHashMap<String, Triple<OnGather, OnJoinHerd, OnSpread>>()
    fun rule(gather: OnGather, join: OnJoinHerd, spread: OnSpread, vararg fields: String) { for (f in fields) r[f] = Triple(gather, join, spread) }
    // Heads.
    rule(OnGather.SUMMED, OnJoinHerd.SUMMED, OnSpread.SUMMED, "adults", "young")
    // Condition: the residents keep these by place ([HexFauna.reserve], fed, protein, dry).
    rule(OnGather.MIXED, OnJoinHerd.MIXED, OnSpread.MIXED, "fat", "fed", "protein", "dry")
    rule(OnGather.FROM_PLACE, OnJoinHerd.KEPT, OnSpread.WHERE_NONE, "birthMonth")
    // Condition that only herds keep: the residents have nothing like it, so it starts afresh and is lost again.
    rule(OnGather.NEW, OnJoinHerd.KEPT, OnSpread.LOST, "surplus", "proteinByMonth", "lean", "warmth", "deathOdds")
    // Alarm and what it knows of hunters and game.
    rule(OnGather.NEW, OnJoinHerd.KEPT, OnSpread.LOST, "chasedOn", "herdsMet", "herdsMetOn", "dietRate")
    // Where it is, has been and means to go.
    rule(OnGather.FROM_PLACE, OnJoinHerd.KEPT, OnSpread.LOST, "place", "formedAt")
    rule(OnGather.NEW, OnJoinHerd.KEPT, OnSpread.LOST, "seasonPlace", "seasonQ", "route", "memory", "recent", "headE", "headS", "home", "path", "days")
    // Who it is, and its ways.
    rule(OnGather.NEW, OnJoinHerd.KEPT, OnSpread.LOST, "id", "species", "sizePref", "temper", "will", "bold", "tag", "parent", "mergedSize", "luck")
    // A band's and a hunter's things: none in a herd of a gathering kind.
    rule(OnGather.NEW, OnJoinHerd.KEPT, OnSpread.LOST, "band", "stores", "storeKgPer", "meatMet")
    return r
}
