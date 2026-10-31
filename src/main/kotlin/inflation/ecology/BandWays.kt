package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.Tuning
import inflation.binomial
import inflation.world.HexLand
import kotlin.random.Random
import inflation.wrapMonth
import inflation.monthsFrom
import inflation.Config

import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import inflation.world.Biome
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * How a band sets its ways for the week: given what it knows of itself ([bandInputs], [WAYS_IN]
 * numbers), it writes [WAYS_OUT] numbers to `out`, each 0 for the scaffold's choice: its effort on
 * each kind of food, the days of food it aims to keep, how it ranks making each tool, and its [BandWay]s.
 */
fun interface BandWays { fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) }
val WAYS_IN = BandInputs.size
/**
 * A band's ways after its effort on each food, its store aim and its ranking of the tools, in the order of
 * [setWays]' outputs; [label] is the output's name in saved networks ([inflation.learning.Schema.WAYS_OUT]).
 */
enum class BandWay(val label: String) {
    SCOUTS("scouts"),
    HOUSES("houses"),
    RAIDS("raids"),
    PALISADES("palisades"),
    SOWING("sowing"),
    GATHERING_RANGE("gathering range"),
    WATCHING_CHILDREN("watching children"),
    NIGHT_FIRE("night fire"),
    SHUNNING_THE_SICK("shunning the sick"),
    EXPLORING("exploring"),
    GIVING("giving"),
    TAKING_FROM_CACHES("taking from caches"),
    CACHING("caching"),
    DRYING("drying"),
    KEEPING_YOUNG_ANIMALS("keeping young animals"),
    SHELTERS("shelters"),
    FEASTING("feasting"),
    APPOINTMENTS("appointments"),
    BURNING_OFF("burning off"),
    REPAYING("repaying"),
    MENDING_FIRST("mending first"),
    STORING_MEAT("storing: meat and fish"),
    STORING_FRUIT("storing: fruit and finds"),
    STORING_ROOTS("storing: roots"),
    STORING_SEED("storing: seed and grain"),
    /** Loads each adult carries on a camp move ([carryLoads]): under one, lighter and faster; over one, relays. */
    CARRY_LOADS("carry loads"),
    /**
     * How a band weighs each activity's ask for days against the others' when they come to more than its week
     * ([BandTime.plan]): the less weighed lose their days first.
     */
    TIME_STORES("time: laying food by"), TIME_DRYING("time: drying"), TIME_SKINS("time: skins"), TIME_SHELTER("time: shelter"),
    TIME_CUSTOMS("time: customs"), TIME_TRAINING("time: training"), TIME_TOOLS("time: tools"), TIME_HOUSES("time: houses"),
    TIME_ORNAMENTS("time: ornaments"), TIME_SOWING("time: sowing"), TIME_PALISADE("time: palisade"), TIME_MATERIALS("time: materials"),
    TIME_CACHING("time: caching"),
    /**
     * How keen it is on each way of building a house it knows ([Skill.BUILDING_WAYS]), against how far its stone, timber and days
     * take each this week ([buildHouse]): they differ in what they cost and in how warm a sunken one keeps and how a mudbrick one
     * wears in the rain ([sunkenM], [MUD_EROSION]).
     */
    BUILD_STONE_FOOTING("build: stone footing"), BUILD_PIT_HOUSE("build: pit house"), BUILD_MUDBRICK("build: mudbrick"),
    /** Hours its people keep awake by turns through the night, armed, by the fire ([Band.watchHours]): sleep lost against beasts kept off. */
    NIGHT_WATCH("night watch");
    /** Its place among the ways' outputs. */
    val slot get() = WaysOut.WAYS + ordinal
}

/** The way that weighs how keen a band is on building houses [w] (one of [Skill.BUILDING_WAYS]). */
fun buildWay(w: Skill): BandWay = when (w) {
    Skill.PIT_HOUSE -> BandWay.BUILD_PIT_HOUSE
    Skill.MUDBRICK -> BandWay.BUILD_MUDBRICK
    else -> BandWay.BUILD_STONE_FOOTING
}

/** The way that weighs [a]'s ask for days ([BandWay.TIME_STORES] and after); none for what comes first (the walk, the week's food and firewood) or is what is left (leisure). */
fun timeWay(a: Activity): BandWay? = when (a) {
    Activity.FOOD -> BandWay.TIME_STORES
    Activity.DRYING -> BandWay.TIME_DRYING
    Activity.SKINS -> BandWay.TIME_SKINS
    Activity.SHELTER -> BandWay.TIME_SHELTER
    Activity.CUSTOMS -> BandWay.TIME_CUSTOMS
    Activity.TRAINING -> BandWay.TIME_TRAINING
    Activity.TOOLS -> BandWay.TIME_TOOLS
    Activity.HOUSES -> BandWay.TIME_HOUSES
    Activity.ORNAMENTS -> BandWay.TIME_ORNAMENTS
    Activity.SOWING -> BandWay.TIME_SOWING
    Activity.PALISADE -> BandWay.TIME_PALISADE
    Activity.MATERIALS -> BandWay.TIME_MATERIALS
    Activity.CACHING -> BandWay.TIME_CACHING
    Activity.FIREWOOD, Activity.MOVING, Activity.WEIRS, Activity.LEISURE, Activity.WELLS -> null
}
/** The way that sets how keenly a band lays food [t] by ([Band.storeWill]). */
fun storeWay(t: Food) = when (t) {
    Food.BIG_GAME, Food.SMALL_GAME, Food.CARRION, Food.BONES, Food.FISH -> BandWay.STORING_MEAT
    Food.FRUIT, Food.FINDS -> BandWay.STORING_FRUIT
    Food.ROOTS -> BandWay.STORING_ROOTS
    Food.SEED, Food.GRAIN -> BandWay.STORING_SEED
}
/** How many ways a band sets ([WaysOut]), and their names as a learned network's `.names` file records them. */
val WAYS_OUT get() = WaysOut.size
val WAYS_OUT_NAMES: List<String> get() = WaysOut.names
