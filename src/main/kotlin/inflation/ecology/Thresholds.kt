package inflation.ecology

/** Lines the model draws in several places, named once so that they stay the same everywhere. */
object Thresholds {
    /** A group fed less than this share of its need this week is hungry: hunters and bands grow restless, and it counts as lean. */
    const val HUNGRY = 0.5f
    /** A herd fed less than this is restless: grazers must keep feeding well, so they move on sooner than hunters or bands. */
    const val HERD_HUNGRY = 0.7f
    /** The food in a place (0..1 of what its kind could find at best) at which a herd values it half as much as it could. */
    const val FOOD_HALF = 0.3f
    /** A place with at least this share of standing water has water to hand (to drink, to work clay, to soak food in). */
    const val WET = 0.05f
    /** A band's house is standing once built this far (0..1). */
    const val HOUSE_STANDING = 0.2f
}
