package com.rajan.meditationtimer

/** One study behind an answer: what it found in plain words, where, and how strong it is. */
data class Study(
    val finding: String,
    /** Authors · journal · year, so it can be looked up. */
    val source: String,
    val evidence: Evidence,
    /** Studied in a related situation (smoking urges, a reading task), not in meditation itself. */
    val indirect: Boolean = false,
)

/** Something that commonly gets in the way of a sit: should you act on it, why, and what to do. */
data class Problem(
    val title: String,
    /** The decision it puts to you: "Should you scratch it?" */
    val question: String,
    /** The short answer, first thing you read. */
    val answer: String,
    /** What it is and what it does to the sit. */
    val impact: String,
    val whatToDo: String,
    val studies: List<Study>,
)

/**
 * Common difficulties in meditation, most common first, each answered from published research.
 * Where the only research is on a neighbouring situation (urges to smoke rather than to scratch),
 * the study is marked indirect rather than passed off as direct evidence. Sources were checked
 * against the publisher or abstract page (October 2026).
 */
object Problems {
    /** The problem for a given day's reading: the list in order, then round again. */
    fun forIndex(index: Int): Problem = ALL[Math.floorMod(index, ALL.size)]

    private const val GOLDBERG = "Goldberg et al. · Psychotherapy Research · 2022"
    private const val LINDAHL = "Lindahl et al. · PLOS ONE · 2017"

    val ALL: List<Problem> = listOf(
        Problem(
            "Restlessness",
            "Should you end the sit early?",
            "Not on the first urge.",
            "Restlessness is one of the most common difficulties, and it comes in waves that pass. Staying through " +
                "one wave is the very skill being trained. Ending early now and then is fine; ending at every urge " +
                "teaches the mind that restlessness wins.",
            "Name it (‘restless’), feel where it is in the body, and make each out-breath a little longer. If you " +
                "often can't finish, choose a shorter sit you can complete: short regular sits beat long abandoned " +
                "ones. The closing bell means there's no need to check the time.",
            listOf(
                Study(
                    "Across a series of experiments, many people found 6 to 15 minutes alone with their thoughts " +
                        "unpleasant; in one, two-thirds of the men gave themselves mild electric shocks rather than just sit.",
                    "Wilson et al. · Science · 2014",
                    Evidence.EXPERIMENT,
                ),
                Study(
                    "Newcomers who meditated 13 minutes a day had better mood, attention and memory after 8 weeks.",
                    "Basso et al. · Behavioural Brain Research · 2019",
                    Evidence.TRIAL,
                ),
            ),
        ),
        Problem(
            "Drowsiness",
            "Is it all right to doze off?",
            "Rest if you need sleep, but a doze isn't practice.",
            "Meditation relaxes the body, so sleepiness is common, especially in the evening, after a meal or when " +
                "you're short of sleep. Asleep, you can't practise noticing and coming back.",
            "Sit more upright, open your eyes slightly and rest them on the floor ahead, let in more light, or sit " +
                "earlier in the day or before eating. If you're simply short of sleep, sleep is the better choice.",
            listOf(
                Study(
                    "In a classic brain-wave study, Transcendental Meditation practitioners showed sleep stages for " +
                        "40–50% of their meditation time.",
                    "Pagano et al. · Science · 1976",
                    Evidence.SMALL,
                ),
                Study(
                    "A review of meditation and wakefulness describes drowsiness as one of the two main ways attention " +
                        "goes off balance, and practice as training alertness as well as calm.",
                    "Britton et al. · Annals of the New York Academy of Sciences · 2014",
                    Evidence.THEORY,
                ),
            ),
        ),
        Problem(
            "An idea you want to write down",
            "Should you stop to write it?",
            "Usually not: park it before you sit.",
            "Unfinished tasks keep pushing into the mind until they're done or planned. Stopping to write breaks the " +
                "sit, and part of your attention stays with the task when you come back. Ideas that arrive while the " +
                "mind is quiet are often good ones, though, and they tend to come back.",
            "Before you begin, take a minute to write down what's on your mind, each with a when (‘call the bank at " +
                "10’). In the sit, label it ‘planning’ and return to the breath. If you truly can't let it go, open " +
                "your eyes, jot three words and go straight back: no reading, no phone.",
            listOf(
                Study(
                    "Unfinished goals caused intrusive thoughts during an unrelated task; making a specific plan for " +
                        "them stopped the intrusions.",
                    "Masicampo & Baumeister · Journal of Personality and Social Psychology · 2011",
                    Evidence.EXPERIMENT,
                    indirect = true,
                ),
                Study(
                    "People who spent 5 minutes writing a to-do list before bed fell asleep about 9 minutes faster " +
                        "than people who wrote about tasks already done.",
                    "Scullin et al. · Journal of Experimental Psychology: General · 2018",
                    Evidence.EXPERIMENT,
                    indirect = true,
                ),
                Study(
                    "After switching away from an unfinished task, part of people's attention stayed with it and their " +
                        "next task suffered (‘attention residue’).",
                    "Leroy · Organizational Behavior and Human Decision Processes · 2009",
                    Evidence.EXPERIMENT,
                    indirect = true,
                ),
            ),
        ),
        Problem(
            "An itch",
            "Should you scratch it?",
            "Not straight away: watch it for a few breaths first.",
            "When the body is still, small sensations get noticed, and an itch can seem to demand action. Scratching " +
                "at once trains the reaction; watching an urge without obeying it is part of what meditation builds.",
            "Notice exactly where it is and what it's like: prickling, tingling, heat. Breathe with it for three " +
                "breaths, since urges rise, peak and fade. If it still pulls after that, scratch slowly and on " +
                "purpose, then return. Moving with attention isn't failing.",
            listOf(
                Study(
                    "Smokers taught to ‘surf’ their urges felt the same urges as others, yet smoked fewer cigarettes " +
                        "over the following week.",
                    "Bowen & Marlatt · Psychology of Addictive Behaviors · 2009",
                    Evidence.TRIAL,
                    indirect = true,
                ),
            ),
        ),
        Problem(
            "Numb legs or pins and needles",
            "Should you move?",
            "Yes, for numbness or sharp pain.",
            "Mild aches are safe to explore, and calm attention can make them bother you less. Numbness is different: " +
                "it means a nerve or blood flow is being pressed, usually by sitting cross-legged.",
            "Shift slowly and on purpose; that's still meditation. Sit higher on a cushion or on a chair, or swap " +
                "which leg is in front. If a leg has gone numb, wait for feeling to return before you stand, so you " +
                "don't put weight on a foot you can't feel.",
            listOf(
                Study(
                    "A 26-year-old who sat cross-legged on a hard floor for 2–3 hours without moving developed a " +
                        "temporary foot drop from a pressed nerve; it took a month to recover.",
                    "Afacan et al. · Cureus · 2025",
                    Evidence.CASE,
                ),
                Study(
                    "After four 20-minute training sessions, meditating during painful heat reduced how unpleasant it " +
                        "felt by 57% and how intense by 40%.",
                    "Zeidan et al. · Journal of Neuroscience · 2011",
                    Evidence.SMALL,
                ),
            ),
        ),
        Problem(
            "Feeling you're doing it wrong",
            "Was a busy-minded sit a bad one?",
            "No. Noticing that you wandered is the practice.",
            "Minds wander about half the time in daily life, so they wander in meditation too. Judging the sit adds " +
                "a second struggle on top of the first.",
            "Count each moment of noticing as one repetition, like a single lift at the gym. When the inner critic " +
                "starts, note ‘judging’ and come back.",
            listOf(
                Study(
                    "Brain scans during breath meditation mapped one repeating cycle: wandering, noticing, returning, staying.",
                    "Hasenkamp et al. · NeuroImage · 2012",
                    Evidence.SMALL,
                ),
                Study(
                    "Training acceptance of whatever came up, not only attention, lowered biological stress reactivity.",
                    "Lindsay et al. · Psychoneuroendocrinology · 2018",
                    Evidence.TRIAL,
                ),
            ),
        ),
        Problem(
            "Noise around you",
            "Should you wait for quiet?",
            "No: sounds can be part of the sit.",
            "The irritation usually comes from the story about a sound (‘they're so loud’) more than the sound itself.",
            "Hear it as sound, its pitch, loudness and rhythm, then return to the breath. Or make sounds your anchor " +
                "for a while. In a noisy place, earplugs or a background sound (Sound & stillness) can help.",
            listOf(
                Study(
                    "Open-monitoring meditation is defined as noticing whatever arises, sounds included, without reacting to it.",
                    "Lutz et al. · Trends in Cognitive Sciences · 2008",
                    Evidence.THEORY,
                ),
            ),
        ),
        Problem(
            "Your phone buzzes",
            "Should you check it?",
            "No, and silence it before you start.",
            "Even a notification you don't answer pulls attention away from what you're doing.",
            "Turn on ‘Silence notifications while I sit’ in Sound & stillness, or put the phone on silent and face " +
                "down. If it buzzes anyway, notice the pull to check, and come back.",
            listOf(
                Study(
                    "Just receiving a call or text notification, without answering it, disrupted an attention task " +
                        "about as much as using the phone.",
                    "Stothart et al. · Journal of Experimental Psychology: Human Perception and Performance · 2015",
                    Evidence.EXPERIMENT,
                ),
            ),
        ),
        Problem(
            "A cough, sneeze or swallow",
            "Should you hold it in?",
            "No: let it happen, then return.",
            "Fighting a reflex takes more attention than allowing it, and trying to push something away tends to make " +
                "it come back stronger.",
            "Cough, swallow or shift, notice the sensation, and return to the breath. Keep water nearby if your " +
                "throat gets dry.",
            listOf(
                Study(
                    "People told not to think of a white bear couldn't stop, and thought about it more afterwards.",
                    "Wegner et al. · Journal of Personality and Social Psychology · 1987",
                    Evidence.EXPERIMENT,
                    indirect = true,
                ),
            ),
        ),
        Problem(
            "Missing a day",
            "Has the habit broken?",
            "No. Just sit today.",
            "One missed day doesn't undo a forming habit. Harshness after a lapse feels motivating but tends to backfire.",
            "Do your usual sit; don't make up for it with a long one. Your streak forgives one missed day a week.",
            listOf(
                Study(
                    "Following 96 people forming new daily habits, missing one opportunity did not materially affect " +
                        "how the habit formed.",
                    "Lally et al. · European Journal of Social Psychology · 2010",
                    Evidence.OBSERVATIONAL,
                ),
                Study(
                    "After a failure, people encouraged to be self-compassionate worked harder to improve.",
                    "Breines & Chen · Personality and Social Psychology Bulletin · 2012",
                    Evidence.EXPERIMENT,
                ),
            ),
        ),
        Problem(
            "Anxiety when watching the breath",
            "Should you keep focusing on the breath?",
            "No need: switch to another anchor.",
            "For some people, close attention to breathing makes it feel forced or tight, and that can feed anxiety. " +
                "Anxiety is one of the unpleasant effects of meditation people report most often.",
            "Rest attention on sounds, the feeling of your hands, or your feet on the floor. Keep your eyes open if " +
                "that helps. A slightly longer out-breath can settle things.",
            listOf(
                Study(
                    "In a US population sample of people who had meditated, anxiety was among the most common " +
                        "meditation-related adverse effects.",
                    GOLDBERG,
                    Evidence.OBSERVATIONAL,
                ),
                Study(
                    "Interviews with 60 meditators documented fear, anxiety and panic among the challenges practice can " +
                        "bring, with responses ranging from mild and brief to severe.",
                    LINDAHL,
                    Evidence.OBSERVATIONAL,
                ),
            ),
        ),
        Problem(
            "Strong emotions or old memories",
            "Should you push through?",
            "No. Pausing, opening your eyes or stopping is wise.",
            "Stillness can let feelings and memories surface. Most pass, but not all, and pushing on through " +
                "overwhelm isn't what practice asks of you.",
            "Open your eyes, feel your feet and the seat, look around the room, and breathe out slowly. Switch to a " +
                "sound or touch anchor, or end the sit. If difficult effects keep coming back or linger after sits, " +
                "cut back and talk to a doctor, therapist or experienced teacher.",
            listOf(
                Study(
                    "Half of 434 US adults who had meditated reported at least one unpleasant meditation-related effect, " +
                        "most often anxiety, re-living traumatic memories or emotional sensitivity; 9% said one affected " +
                        "their daily functioning.",
                    GOLDBERG,
                    Evidence.OBSERVATIONAL,
                ),
                Study(
                    "Across 83 studies, about 8% of participants had an adverse effect, most often anxiety or low mood.",
                    "Farias et al. · Acta Psychiatrica Scandinavica · 2020",
                    Evidence.REVIEW,
                ),
            ),
        ),
        Problem(
            "Unusual sensations or perceptions",
            "Should you chase them, or worry?",
            "Neither: note them and return.",
            "Tingling, lights, the body feeling larger or smaller, or rushes of energy are reported across traditions. " +
                "They usually pass; treating them as special or as alarming tends to hook attention.",
            "Note ‘seeing’ or ‘tingling’ and come back to your anchor. If they're distressing or carry on outside " +
                "sits, practise less and talk to an experienced teacher or a doctor.",
            listOf(
                Study(
                    "Interviews with 60 Buddhist meditators found experiences in seven areas, including perception, " +
                        "body sensations and sense of self, ranging from not distressing at all to severe.",
                    LINDAHL,
                    Evidence.OBSERVATIONAL,
                ),
            ),
        ),
        Problem(
            "Dizziness during breathing exercises",
            "Should you breathe more deeply?",
            "No: slower, not bigger.",
            "Big, fast breaths blow off carbon dioxide, which narrows the blood vessels of the brain and can make you " +
                "light-headed.",
            "Breathe gently through the nose and make each breath slower rather than larger. If you feel dizzy, go " +
                "back to ordinary breathing until it passes.",
            listOf(
                Study(
                    "A medical review explains how low carbon dioxide from over-breathing cuts blood flow to the brain.",
                    "Laffey & Kavanagh · New England Journal of Medicine · 2002",
                    Evidence.THEORY,
                ),
            ),
        ),
        Problem(
            "Light-headed when you stand up",
            "Should you get straight up?",
            "No: tense your legs first, then rise slowly.",
            "Blood pressure can drop sharply in the first seconds after standing, especially in teenagers and young " +
                "adults, bringing dizziness or blurred vision.",
            "Before standing, tense your leg and buttock muscles for a few seconds and flex your feet. Get up in " +
                "stages and stand still for a breath before you walk.",
            listOf(
                Study(
                    "In young women, tensing or pre-activating the leg muscles before standing reduced the drop in " +
                        "blood pressure on standing and the symptoms that came with it.",
                    "Sheikh et al. · Heart Rhythm · 2022",
                    Evidence.EXPERIMENT,
                ),
            ),
        ),
    )
}
