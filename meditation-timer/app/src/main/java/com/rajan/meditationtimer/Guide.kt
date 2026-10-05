package com.rajan.meditationtimer

/** One topic in the Guide: a title, a line saying what's inside, and a few short points. */
data class GuideTopic(val title: String, val summary: String, val points: List<String>)

/**
 * The Guide: reference for when you want it, not a tour you must sit through. Each topic opens
 * on its own (one at a time, like a help centre's FAQ), so the list stays short on screen.
 */
object Guide {
    /** [backupLocation] is where the automatic copy is saved, or null where there isn't one. */
    fun topics(backupLocation: String?): List<GuideTopic> = listOf(
        GuideTopic(
            "How to sit",
            "Three things are all you need",
            Welcome.howToSit.map { (title, body) -> "$title. $body" } +
                "Restless, sleepy or busy-minded sits count too. They're part of it, not a failed sit.",
        ),
        GuideTopic(
            "How long, and how often",
            "Short and regular beats long and rare",
            listOf(
                "Start with 5 to 10 minutes. Lengthen the sit when it starts to feel short, not before.",
                "Sit on most days rather than now and then. In one study, beginners who sat 13 minutes a " +
                    "day were calmer and more attentive after 8 weeks, but not yet after 4 (Basso et al., 2019). " +
                    "Give it two months before judging.",
                "The weekly goal (Practice tools) is days a week, not minutes. Your streak forgives one " +
                    "missed day each week, so a busy day doesn't wipe it out.",
            ),
        ),
        GuideTopic(
            "A sit, step by step",
            "From Begin to your journal",
            listOf(
                "Choose a length and tap Begin. If check-ins are on, note how you feel, then tap Begin " +
                    "again when you're ready. Nothing starts until you do.",
                "Settle-in breaths come first (Bells & breaths): slow breathing, in for 4 and out for 6, with a " +
                    "light buzz at each. Then the opening bell starts the sit and the breath is left natural. Interval " +
                    "bells bring you back; the closing bell says the end is near.",
                "Pause holds the time and the bells. End gives you ${SessionClock.END_CONFIRM_MS / 1000} " +
                    "seconds to change your mind. Sits under a minute aren't saved.",
                "Afterwards, say how you feel and how the sit went, and add a line to your journal if you like. All optional.",
                "Before you stand, tense your legs and flex your feet for a few seconds, then rise slowly: blood " +
                    "pressure can dip in the first seconds of standing.",
            ),
        ),
        GuideTopic(
            "Noticing a wandering mind",
            "Tap when you catch it, then come back",
            listOf(
                "With Count distractions on (Practice tools), tap anywhere on the screen or press a volume " +
                    "key each time you notice the mind has wandered. A light buzz confirms it.",
                "A higher count isn't worse: it means you noticed more often. History shows the trend.",
                "The screen stays on while counting so taps register. Your brightness isn't changed.",
            ),
        ),
        GuideTopic(
            "Bells, sound and silence",
            "The Bells & breaths and Sound & stillness rows",
            listOf(
                "Bells & breaths: settle-in breaths, and when the opening, interval and closing bells ring.",
                "Bell, Bell + vibrate, or Vibrate only for sitting next to someone. Test plays it at the volume you set.",
                "Background sound: rain, birdsong, both, or a recording of your own. It fades in, dips under " +
                    "every bell and fades out at the end. Listen plays 10 seconds.",
                "Silence notifications while I sit turns on Do Not Disturb for the sit, and off again after.",
            ),
        ),
        GuideTopic(
            "Breathe and Mala",
            "The other two tabs",
            listOf(
                "Breathe paces your breath: Coherent to calm, Box to steady, 4-7-8 to wind down, Bhramari " +
                    "(humming) and Nadi Shodhana (alternate nostril). A small vibration marks each change, " +
                    "so eyes can stay closed. A few minutes before a sit helps you settle.",
                "The attention check, at the bottom of Breathe, measures how steady your attention is by counting breaths in nines.",
                "Mala counts mantra or breath rounds of 27, 54 or 108 beads. Tap the circle or press a " +
                    "volume key; a bell rings at the end of each round.",
            ),
        ),
        GuideTopic(
            "Lessons",
            "A short idea for each day you sit",
            listOf(
                "A new lesson comes with each day you sit, so a missed day never skips one. Each has something " +
                    "to try in today's sit; Why & research says where it comes from.",
                "Earlier lessons opens the ones you've had so far.",
                "The app opens each day on the day's reading: the lesson with all its research, then one common " +
                    "problem. It shows once a day; switch it off in Practice tools.",
            ),
        ),
        GuideTopic(
            "History and looking back",
            "Overview, Trends and Sessions",
            listOf(
                "Overview: this week against your goal, the month in review, and your highlights. Tap a highlight for Trends.",
                "Trends: what a sit changes in how you feel, how often you noticed wandering, and a calendar of the last weeks.",
                "Sessions: every sit, newest first, with your notes.",
                "How you compare (Overview): how often you sit against published surveys of experienced " +
                    "meditators, adults in India and new app users.",
                "On the Sit screen, a note you wrote a week, a month or a year ago today may come back. Hide puts it away until tomorrow.",
            ),
        ),
        GuideTopic(
            "Shortcuts and reminders",
            "Sit in one tap",
            listOf(
                "Long-press the app icon for Start my sit, Breathe and Mala. Start my sit begins your usual sit at once.",
                "Add the widget to your home screen: it shows your week, and Sit starts your usual sit.",
                "Daily reminder (Practice tools): tie it to a habit you already have, like \"after morning tea\". " +
                    "It's skipped on days you've already sat.",
            ),
        ),
        GuideTopic(
            "Your data",
            "It stays on your phone",
            buildList {
                add("No account, no ads, nothing sent anywhere unless you choose to back up.")
                if (backupLocation != null) {
                    add("A copy of your history, without journal notes, is saved automatically to $backupLocation.")
                }
                add("Backup (top of History) saves everything, notes included: to a file you choose, or to a private folder in your Google Drive.")
                add("After a reinstall or on a new phone, Restore brings it all back.")
            },
        ),
    )
}
