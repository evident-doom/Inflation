package inflation.ecology

/**
 * What is wrong with [g]'s state, if anything: each band's stocks not below nothing nor other than a number,
 * its shares within 0..1, the old and the age spans no more than its adults, and the fields that go in
 * parts agreeing (an appointment's hex and month, a raid's target, hex and weeks, an unlucky hex and its
 * weeks, the herd and its tameness). Empty when all is well.
 */
fun HexGroup.violations(): List<String> {
    val bad = ArrayList<String>()
    fun check(what: String, ok: Boolean) { if (!ok) bad += "group $id (${species.name}): $what" }
    fun FloatArray.allIn(lo: Float, hi: Float) = all { it.isFinite() && it >= lo && it <= hi }
    check("adults $adults, young $young", adults >= 0 && young >= 0)
    check("fed $fed, reserves $fat", fed.isFinite() && fed in 0f..1.0001f && fat.isFinite() && fat >= 0f)
    check("is a band ${band != null} for a kind that is people ${species.human}", (band != null) == species.human)
    val b = band ?: return bad
    check("goods ${b.goods.a.toList()}", b.goods.a.allIn(0f, Float.MAX_VALUE))
    check("stores ${stores.a.toList()}", stores.a.allIn(0f, Float.MAX_VALUE))
    check("cache ${b.cache.a.toList()}", b.cache.a.allIn(0f, Float.MAX_VALUE))
    check("kit ${b.kit.a.toList()}", b.kit.a.allIn(0f, 1.0001f))
    check("make of its tools ${b.fine.a.toList()}", b.fine.a.allIn(0f, 1.0001f))
    check("skills ${b.skills.a.toList()}", b.skills.a.allIn(0f, Float.MAX_VALUE))
    for ((name, v) in listOf("house" to b.house, "palisade" to b.palisade, "hurt" to b.hurt, "lame" to b.lame,
        "night wood" to b.nightWood))
        check("$name $v", v.isFinite() && v in 0f..1.0001f)
    // (A shelter is built up to the band's will for it, at most twice the default's.)
    check("shelter ${b.shelter}", b.shelter.isFinite() && b.shelter in 0f..2.0001f)
    for (i in Illness.entries) check("$i: sick ${b.sick[i]}, immune ${b.immune[i]}",
        b.sick[i] >= 0f && b.immune[i] >= 0f && b.sick[i] + b.immune[i] <= 1.0001f)
    check("spare days ${b.spareDays}", b.spareDays.isFinite() && b.spareDays >= 0f)
    check("elders ${b.elders} and age spans ${b.ageStage.toList()} of $adults adults",
        b.elders >= 0 && b.ageStage.all { it >= 0 } && b.elders + b.ageStage.sum() <= adults)
    check("rite places ${b.rite.toList()}", b.rite.all { it >= -1 })
    check("ties ${b.ties}", b.ties.values.all { it.isFinite() })
    check("grudges ${b.grudge.toList()}", b.grudge.allIn(0f, Float.MAX_VALUE))
    check("lineage ${b.lineage.toList()}", b.lineage.allIn(0f, Float.MAX_VALUE))
    check("inbred ${b.inbred}", b.inbred.isFinite() && b.inbred in 0f..1f)
    check("hold on its rites ${b.riteStrength}", b.riteStrength.isFinite() && b.riteStrength >= 0f)
    check("herd ${b.herd.toList()}", b.herd.allIn(0f, Float.MAX_VALUE))
    check("young in the herd ${b.herdYoung.toList()}", b.herdYoung.allIn(0f, Float.MAX_VALUE))
    check("herd of ${b.herd.size} kinds, tameness of ${b.herdTame.size}", b.herd.size == b.herdTame.size)
    check("tameness ${b.herdTame.toList()}", b.herdTame.allIn(0f, 1.0001f))
    b.appointment?.let { check("appointment at ${it.hex} in month ${it.month}", !it.hex.none && it.month in 0..11) }
    b.raid?.let { check("raid on band ${it.on} at ${it.hex} for ${it.weeks} weeks", !it.hex.none && it.on >= 0 && it.weeks > 0) }
    b.unlucky?.let { check("unlucky ${it.hex} for ${it.weeks} weeks", !it.hex.none && it.weeks > 0) }
    return bad
}
