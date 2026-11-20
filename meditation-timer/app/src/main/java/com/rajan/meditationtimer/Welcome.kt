package com.rajan.meditationtimer

/**
 * The first-run welcome asks two things, as Headspace does: how much you've sat before (which
 * sets the length and the weekly goal) and when in your day a sit could go (which sets the
 * reminder). Then it teaches how to sit and offers the first sit. No tour of the screens: tours
 * shown up front don't help people use an app (NN/g), and each screen here explains itself.
 */
enum class Experience(val label: String, val detail: String, val minutes: Int, val weeklyGoal: Int) {
    NEW("I'm new to this", "Start small: 5 minutes, 3 days a week", 5, 3),
    SOME("I've tried it a little", "10 minutes, 4 days a week", 10, 4),
    REGULAR("I sit regularly", "20 minutes, 5 days a week", 20, 5),
}

/** A moment you already pass through each day, so the sit has a cue to hang on. */
enum class SitTime(val label: String, val minuteOfDay: Int?, val cue: String) {
    WAKING("After I wake up", 7 * 60, "After waking up"),
    LUNCH("At lunchtime", 13 * 60, "At lunchtime"),
    HOME("When I get home", 18 * 60 + 30, "After getting home"),
    BED("Before bed", 21 * 60 + 30, "Before bed"),
    LATER("I'll decide later", null, ""),
    ;

    /** "Reminder around 7:00 am", or what choosing it means when there's no time. */
    val detail: String
        get() = minuteOfDay?.let { "Reminder around ${Reminder(true, it, cue).timeLabel}" } ?: "No reminder for now"
}

object Welcome {
    const val STEPS = 3

    /**
     * Only for someone who has never used the app: not finished or skipped before, no sit
     * settings ever saved (every Begin saves them) and no history (which a restore brings back).
     */
    fun needed(done: Boolean, everSaved: Boolean, hasHistory: Boolean): Boolean = !done && !everSaved && !hasHistory

    /** The reminder after answering "when could you sit?". "Later" leaves it exactly as it was. */
    fun reminderFor(time: SitTime, current: Reminder): Reminder =
        time.minuteOfDay?.let { Reminder(enabled = true, minuteOfDay = it, cue = time.cue) } ?: current

    /** The three things to know before a first sit, shared by the welcome and the guide. */
    val howToSit: List<Pair<String, String>> = listOf(
        "Sit comfortably" to "Upright but not stiff; a chair is fine. Let your eyes close, or rest them on the floor ahead.",
        "Feel the breath" to "Notice it where it's clearest: the nose, the chest or the belly. No need to change it.",
        "Come back, again and again" to "The mind will wander, many times. Noticing that and returning to the breath " +
            "is the practice itself, not a mistake.",
    )
}
