package inflation.ecology

/** What becomes of a band's field when a band splits off it ([Band.splitInto]). */
enum class OnSplit {
    /** The new band gets a copy. */
    COPIED,
    /** Divided between the two by heads. */
    SHARED,
    /** Its practised people go one way or the other, drawn person by person. */
    DRAWN,
    /** Each band's own adults. */
    SET,
    /** The new band starts as any new band does; the old keeps its own. */
    FRESH,
    /** A choice its ways make afresh each week. */
    CHOSEN,
}

/** What becomes of a band's field when another band joins it ([Band.absorb]). */
enum class OnJoin {
    /** The better of the two. */
    MAX,
    /** Known to either. */
    ANY,
    /** Added together. */
    SUM,
    /** Blended by the two bands' adults (kit's make by its amount, salting by the dried food, grain's by the seed, tameness by the animals). */
    BLENDED,
    /** The joining band's is lost; the band keeps its own. */
    LOST,
    /** A choice its ways make afresh each week. */
    CHOSEN,
}

/** [Band.CARRY]: each field's rule, as [Band.splitInto] and [Band.absorb] carry it out. */
internal fun carryRules(): Map<String, Pair<OnSplit, OnJoin>> {
    val r = LinkedHashMap<String, Pair<OnSplit, OnJoin>>()
    fun rule(split: OnSplit, join: OnJoin, vararg fields: String) { for (f in fields) r[f] = split to join }
    // Know-how and lore.
    rule(OnSplit.COPIED, OnJoin.MAX, "skills", "plants", "renown", "grudge")
    rule(OnSplit.COPIED, OnJoin.ANY, "knowsSkill", "knows", "known")
    rule(OnSplit.DRAWN, OnJoin.SUM, "holders")
    rule(OnSplit.COPIED, OnJoin.LOST, "lastUsed", "seasonPlace", "seasonQ", "weeks", "traditions", "words", "rite", "riteStrength", "knownHomes", "knownLineage")
    // Health.
    rule(OnSplit.COPIED, OnJoin.BLENDED, "sick", "immune", "hurt", "lame")
    rule(OnSplit.FRESH, OnJoin.LOST, "cleaned", "nursed", "beastLoss")
    // People.
    rule(OnSplit.SET, OnJoin.SUM, "lastAdults")
    rule(OnSplit.SHARED, OnJoin.SUM, "elders", "ageStage", "goods", "kept")
    rule(OnSplit.COPIED, OnJoin.BLENDED, "kit", "fine", "salted", "leanLost", "lineage", "inbred")
    // Camp, home and raids.
    rule(OnSplit.COPIED, OnJoin.LOST, "campWeeks", "campPlace", "appointment", "houseHex", "house", "palisade", "fire")
    rule(OnSplit.FRESH, OnJoin.LOST, "cache", "cacheKgPer", "cachePlace", "raid", "unlucky",
        "shelter", "atHome", "nightWood", "hearthWood", "spareDays", "dogShare", "dryAsk", "skinAsk")
    // Ties.
    rule(OnSplit.COPIED, OnJoin.MAX, "ties")
    // (Favours stay with the band that was helped or helped: the new band owes and is owed nothing.)
    rule(OnSplit.FRESH, OnJoin.SUM, "favours")
    // Wealth and animals.
    rule(OnSplit.SHARED, OnJoin.SUM, "ornaments", "exotic", "herd")
    rule(OnSplit.COPIED, OnJoin.BLENDED, "herdTame", "seedDomestic")
    rule(OnSplit.COPIED, OnJoin.LOST, "sinceRaided")
    // Its ways' choices.
    rule(OnSplit.CHOSEN, OnJoin.CHOSEN, "storeAim", "will", "makeFirst", "effort")
    return r
}
