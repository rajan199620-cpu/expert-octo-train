package com.rajan.meditationtimer

import java.time.LocalDate

/** How strong the evidence behind a principle is, strongest first. */
enum class Evidence(val label: String) {
    REVIEW("Meta-analysis / systematic review"),
    TRIAL("Randomised trial"),
    EXPERIMENT("Lab experiment"),
    OBSERVATIONAL("Observational study"),
    SMALL("Small study — treat as preliminary"),
    THEORY("Theory / narrative review"),
}

/** One lesson of the course: a principle, something to try today, and the research behind it. */
data class Principle(
    val theme: String,
    val title: String,
    val body: String,
    val practice: String,
    /** What the study or review found, in plain words. */
    val finding: String,
    /** Authors · journal · year, so it can be looked up. */
    val source: String,
) {
    val evidence: Evidence get() = Principles.evidenceFor(source)
}

/**
 * A research-based course in meditation technique, in teaching order: why practise → building
 * the habit → training attention → posture and alertness → breath → thoughts → body → emotions →
 * open awareness → kindness → daily life. At the end the course starts again.
 *
 * Every principle rests on a published study or review whose findings were checked against the
 * publisher or abstract page (Sept 2026), and is tagged with how strong that evidence is.
 * Instructions without research behind them were left out rather than padded in.
 *
 * The course advances one lesson per day you actually sit, so skipped days never skip lessons.
 * Progress is derived from the session history (which Restore brings back), not stored
 * separately, so a reinstall followed by Restore picks up exactly where you were.
 */
object Principles {
    /** Lessons completed = distinct days with a logged sit before today. Stable all of today. */
    fun lessonIndex(sitDays: Set<LocalDate>, today: LocalDate): Int = sitDays.count { it.isBefore(today) }

    fun forLesson(index: Int): Principle = ALL[Math.floorMod(index, ALL.size)]

    /** "Lesson 12", counting on across repeats of the course. */
    fun lessonNumber(index: Int): Int = index + 1

    /**
     * Lessons reached so far, newest first: the current one back to lesson 1, capped at one full
     * course so each principle appears once. Lessons ahead stay hidden.
     */
    fun archive(currentIndex: Int): List<Pair<Int, Principle>> =
        (currentIndex downTo maxOf(0, currentIndex - ALL.size + 1)).map { it to forLesson(it) }

    /** Evidence strength by first author; every cited source is classified (enforced by a test). */
    fun evidenceFor(source: String): Evidence = when (source.substringBefore(" ")) {
        "Goyal", "Farias", "Parsons", "Gollwitzer", "Zaccaro", "Kirby", "Zeng", "Robinson", "Rusch" -> Evidence.REVIEW
        "Kral", "Basso", "Mrazek", "Nair", "Balban", "Jain", "Bowen", "Fredrickson", "Neff", "Teut" -> Evidence.TRIAL
        "Wegner", "Breines", "Hutcherson", "Colzato", "Emmons", "Bratman" -> Evidence.EXPERIMENT
        "Killingsworth", "Lally", "Jose", "Levinson" -> Evidence.OBSERVATIONAL
        "Hasenkamp", "Brewer", "Zeidan", "Lieberman" -> Evidence.SMALL
        "Lindsay" -> if ("Clinical Psychology Review" in source) Evidence.THEORY else Evidence.TRIAL
        "Lutz", "Britton", "Bernstein", "Farb", "Lehrer" -> Evidence.THEORY
        else -> error("Unclassified source: " + source)
    }

    private class Section(val theme: String) {
        val items = mutableListOf<Principle>()
        fun p(title: String, body: String, practice: String, finding: String, source: String) {
            items += Principle(theme, title, body, practice, finding, source)
        }
    }

    private fun section(theme: String, block: Section.() -> Unit) = Section(theme).apply(block).items

    private const val KILLINGSWORTH = "Killingsworth & Gilbert · Science · 2010"
    private const val HASENKAMP = "Hasenkamp et al. · NeuroImage · 2012"
    private const val LEVINSON = "Levinson et al. · Frontiers in Psychology · 2014"
    private const val LINDSAY_2018 = "Lindsay et al. · Psychoneuroendocrinology · 2018"
    private const val MAT = "Lindsay & Creswell · Clinical Psychology Review · 2017"
    private const val BALBAN = "Balban et al. · Cell Reports Medicine · 2023"
    private const val BRITTON = "Britton et al. · Annals of the New York Academy of Sciences · 2014"
    private const val NAIR = "Nair et al. · Health Psychology · 2015"
    private const val BERNSTEIN = "Bernstein et al. · Perspectives on Psychological Science · 2015"
    private const val FARB = "Farb et al. · Frontiers in Psychology · 2015"
    private const val ZEIDAN = "Zeidan et al. · Journal of Neuroscience · 2011"
    private const val BOWEN = "Bowen & Marlatt · Psychology of Addictive Behaviors · 2009"
    private const val LUTZ = "Lutz et al. · Trends in Cognitive Sciences · 2008"
    private const val FREDRICKSON = "Fredrickson et al. · Journal of Personality and Social Psychology · 2008"
    private const val LALLY = "Lally et al. · European Journal of Social Psychology · 2010"

    val ALL: List<Principle> = listOf(
        section("Why practise") {
            p(
                "Minds wander half the time",
                "Attention drifts far more than we realise. Meditation trains the moment of noticing, so more of life is spent where you actually are.",
                "Today, notice roughly how often you catch yourself somewhere else — just to see, not to judge.",
                "Phone check-ins with 2,250 adults found minds wandering in 46.9% of samples. People were less happy while wandering, and the timing suggested the wandering came first.",
                KILLINGSWORTH,
            )
            p(
                "The four-step cycle",
                "Every sit repeats one loop: the mind wanders, you notice, you bring attention back, you stay for a while. Seen this way, wandering is part of the practice, not a failure of it.",
                "Watch for all four steps today: wandering, noticing, returning, staying.",
                "Brain scans during breath meditation mapped exactly this cycle — mind-wandering, awareness of it, shifting attention, sustained attention — each with its own brain network.",
                HASENKAMP,
            )
            p(
                "Noticing is the repetition",
                "The instant you realise you drifted is the most important moment of the sit. Each one is a repetition, like a single lift in the gym.",
                "Each time you notice wandering, give it a tiny inner ‘good’ before returning.",
                "The moment people noticed their mind had wandered, the brain’s salience network became active — the system that flags what needs attention.",
                HASENKAMP,
            )
            p(
                "Two skills: notice and allow",
                "Mindfulness has two parts: monitoring what is happening, and accepting it. Monitoring alone can make you more reactive; acceptance is what calms the reaction.",
                "When you notice something unpleasant, add a silent ‘let it be’.",
                "Monitor and Acceptance Theory proposes that attention monitoring raises awareness and emotional reactivity, while acceptance regulates the emotional response.",
                MAT,
            )
            p(
                "Acceptance lowers stress",
                "Allowing an experience is not giving up. It is dropping the extra fight against what is already here — and that changes how the body handles stress.",
                "Practise ‘noticing plus allowing’ for the whole sit.",
                "In a randomised trial of a 15-lesson smartphone course, training in monitoring plus acceptance reduced biological stress reactivity more than monitoring alone or a control course.",
                LINDSAY_2018,
            )
            p(
                "Realistic expectations",
                "Meditation helps, but modestly and gradually. Expecting fireworks leads to disappointment; expecting small, steady change keeps you going.",
                "Let go of any hoped-for result today and simply sit.",
                "A review of 47 trials (3,320 people) found moderate evidence that mindfulness programmes ease anxiety, depression and pain — small-to-moderate effects, and no evidence they beat other active treatments such as exercise or medication.",
                "Goyal et al. · JAMA Internal Medicine · 2014",
            )
            p(
                "Know when to step back",
                "For most people meditation is safe, but it can sometimes stir up anxiety or low mood. Grounding yourself, shortening sits or pausing are wise choices. If difficulties persist, talk to a doctor or therapist.",
                "If a sit ever feels overwhelming, open your eyes, feel your feet and look around the room.",
                "A review of 83 studies (6,703 people) found adverse effects in about 8% of participants, most often anxiety and low mood — sometimes in people with no previous mental-health history.",
                "Farias et al. · Acta Psychiatrica Scandinavica · 2020",
            )
        },
        section("Building the habit") {
            p(
                "Short sits, many weeks",
                "You don’t need long sessions. A short daily practice kept up for weeks is what changes things — and the early weeks may feel like nothing is happening.",
                "Commit to sitting every day for the next eight weeks, whatever today’s sit is like.",
                "Non-meditators who did 13 minutes of guided meditation daily had better mood, attention and memory and less anxiety after 8 weeks — but not yet at 4 weeks.",
                "Basso et al. · Behavioural Brain Research · 2019",
            )
            p(
                "Practice time adds up",
                "More practice tends to bring more benefit. Showing up is the main thing; lengthening sits gently over time adds to it.",
                "If today’s sit feels easy, consider adding five minutes.",
                "Across mindfulness courses, participants practised about 30 minutes a day, six days a week, and more home practice went with better outcomes.",
                "Parsons et al. · Behaviour Research and Therapy · 2017",
            )
            p(
                "Same time, same cue",
                "Habits form by repeating a behaviour in the same situation until it becomes automatic. Tie your sit to something you already do every day.",
                "Choose one fixed cue for your sit — after tea, after brushing your teeth — and keep it.",
                "People repeating a daily behaviour in the same context took a median of 66 days to make it close to automatic.",
                LALLY,
            )
            p(
                "Your own pace",
                "There is no fixed number of days to form a habit. Some people get there fast, some slowly; both are normal.",
                "If sitting still feels effortful, remind yourself it’s early days — and sit anyway.",
                "In the same study, the time to reach near-full automaticity ranged from 18 to 254 days.",
                LALLY,
            )
            p(
                "If–then plans",
                "A vague goal (‘I’ll meditate more’) is easy to skip. A specific plan — if this happens, then I sit — makes follow-through far more likely.",
                "Write one line: ‘If it is [time or cue], then I sit for [minutes].’",
                "A meta-analysis of 94 tests found that if–then plans had a medium-to-large effect on reaching goals (d = 0.65).",
                "Gollwitzer & Sheeran · Advances in Experimental Social Psychology · 2006",
            )
            p(
                "Be kind after a scattered sit",
                "Harshness after a messy sit feels motivating but isn’t. Meeting setbacks with self-compassion makes people try harder next time.",
                "After today’s sit, whatever it was like, say one kind sentence to yourself.",
                "In four experiments, people who responded to failure with self-compassion were more motivated to improve and studied longer after failing a test.",
                "Breines & Chen · Personality and Social Psychology Bulletin · 2012",
            )
            p(
                "Practise for your days, not your brain scan",
                "You may read that a few weeks of meditation visibly reshapes the brain. The strongest evidence so far says that claim was premature. Judge practice by how your days feel, not by headlines.",
                "This week, notice one small change in an ordinary day — that is the evidence that matters.",
                "An early study reported grey-matter increases after an 8-week mindfulness course, but the largest and most rigorously controlled test — 218 people across two randomised trials — found no structural brain changes from the same course.",
                "Kral et al. · Science Advances · 2022",
            )
        },
        section("Training attention") {
            p(
                "Choose one object",
                "Focused-attention meditation means choosing one object — usually the breath — and returning to it again and again.",
                "Choose your anchor (nostrils, chest or belly) before the opening bell and keep it for the whole sit.",
                "Researchers describe two core styles: voluntarily focusing on a chosen object, and non-reactive monitoring of whatever arises moment to moment.",
                LUTZ,
            )
            p(
                "Count the breath",
                "Counting breaths gives attention a clear job and shows you straight away when you’ve drifted.",
                "Count out-breaths from 1 to 10, then start again. Lost count? Back to 1.",
                "Across four studies (400+ people), breath-counting skill went with less mind-wandering and better mood, and 4 weeks of breath-counting training reduced mind-wandering.",
                LEVINSON,
            )
            p(
                "Losing count is information",
                "Losing count isn’t failure — it shows you where attention went. Over time you lose count less often; that is the skill growing.",
                "Notice when you lose count and what pulled you away, then begin again at 1.",
                "Breath counting was validated as a measure of mindfulness: long-term meditators counted more accurately than age-matched non-meditators.",
                LEVINSON,
            )
            p(
                "Catch it sooner",
                "With practice, the gap between drifting and noticing shrinks — from minutes to seconds. That shrinking gap is concentration developing.",
                "Try to catch the next thought as it starts, before it becomes a story.",
                "Breath-counting skill went with greater meta-awareness — knowing what the mind is doing — and training it reduced mind-wandering.",
                LEVINSON,
            )
            p(
                "Return, then stay",
                "After you come back, the next job is to stay. Sustained attention is steady and light — follow the breath rather than gripping it.",
                "After each return, try to stay with five full breaths.",
                "After returning to the breath, activity shifted to executive-control regions, and part of that network stayed active while attention was held on the breath.",
                HASENKAMP,
            )
            p(
                "Quieting the ‘me’ network",
                "Much mind-wandering is about ourselves — plans, worries, stories. Returning to the breath again and again trains the mind out of that loop.",
                "When you notice a ‘me’ story (my plans, my problems), note ‘self-talk’ and return.",
                "Experienced meditators showed less activity in the default mode network — linked to self-referential thinking and mind-wandering — across concentration, loving-kindness and open-awareness practice.",
                "Brewer et al. · PNAS · 2011",
            )
            p(
                "It carries into daily life",
                "Attention training doesn’t stay on the cushion. Even a short course can reduce distraction in demanding everyday tasks.",
                "Notice today whether you catch distraction sooner while working or reading.",
                "In a randomised trial, a 2-week mindfulness course reduced distracting thoughts and improved working memory and reading-test scores — about a 16-percentile-point gain.",
                "Mrazek et al. · Psychological Science · 2013",
            )
            p(
                "A busy mind gains most",
                "If your mind feels especially scattered, that isn’t a sign meditation isn’t for you. It may be where it helps most.",
                "If today’s sit is busy, treat every return as especially valuable.",
                "In the same trial, the gains came through reduced mind-wandering among the people who were most prone to distraction at the start.",
                "Mrazek et al. · Psychological Science · 2013",
            )
            p(
                "Present moments are happier moments",
                "Being somewhere else in your head has a cost. Across daily life, a wandering mind tends to be a less happy one.",
                "Bring full attention to one ordinary activity today — washing, walking, drinking tea.",
                "People were generally less happy when their minds wandered than when focused on what they were doing, and wandering tended to precede, not follow, the dip in mood.",
                KILLINGSWORTH,
            )
        },
        section("Posture and alertness") {
            p(
                "Sit upright",
                "An upright posture isn’t just tradition. How you sit feeds back into how you feel.",
                "Before the opening bell, lengthen your spine and let your shoulders settle.",
                "In a randomised trial, people held upright during a stressful task kept higher self-esteem, had better mood and spoke more than people held in a slumped posture.",
                NAIR,
            )
            p(
                "Posture before pressure",
                "The effect isn’t limited to meditation. Before something stressful, how you hold yourself can help you meet it.",
                "Before a stressful moment today, sit or stand upright for a few breaths.",
                "Upright sitting helped people keep their self-esteem and mood through a stressful speech task, compared with slumping.",
                NAIR,
            )
            p(
                "Alert and relaxed",
                "Meditation is not just relaxation. The aim is relaxed alertness — not so tense that you’re restless, not so loose that you drift into sleep.",
                "Check halfway: too wired, too sleepy, or balanced? Adjust.",
                "A review of meditation and wakefulness notes that practice guards against both over-arousal (restlessness) and under-arousal (drowsiness), and that modern uses over-emphasise relaxation.",
                BRITTON,
            )
            p(
                "When drowsy",
                "Drowsiness is one side of that balance. Brighten the practice: sit taller, let some light in through the eyes, or sit at a more alert time of day.",
                "At the first sign of dullness, straighten up and open your eyes slightly.",
                "Traditional descriptions of meditation treat drowsiness and sleep as one of the two main ways attention goes off balance.",
                BRITTON,
            )
            p(
                "When restless",
                "Restlessness is the other side. Lengthening the out-breath is one of the quickest ways to settle an over-active system.",
                "If restless today, make each out-breath a little longer than the in-breath.",
                "Five minutes a day of exhale-focused breathing improved mood and lowered breathing rate more than the same time spent in mindfulness meditation, over a month.",
                BALBAN,
            )
        },
        section("Breath") {
            p(
                "Slow breathing",
                "Breathing slowly — well under ten breaths a minute — shifts the body toward a calmer state.",
                "Try 3 minutes of the Coherent rhythm on the Breathe tab before today’s sit.",
                "A systematic review found slow breathing (under 10 breaths a minute) increased heart-rate variability and was linked to better emotional control and wellbeing in healthy people.",
                "Zaccaro et al. · Frontiers in Human Neuroscience · 2018",
            )
            p(
                "About six breaths a minute",
                "At around six breaths a minute, heart rhythm and breath fall into step, exercising the body’s own blood-pressure reflex.",
                "Use the Coherent rhythm (5.5 s in, 5.5 s out) for a few minutes.",
                "Breathing at about 4.5–6.5 breaths per minute increases the natural rise and fall of heart rate with each breath; the leading explanation is a strengthened baroreflex.",
                "Lehrer & Gevirtz · Frontiers in Psychology · 2014",
            )
            p(
                "The long exhale",
                "The out-breath is the calming half of breathing. Lengthening it is a simple, quick lever on mood.",
                "For ten breaths, make the exhale noticeably longer than the inhale.",
                "Of the practices compared in a month-long trial, breathwork — especially exhale-focused ‘cyclic sighing’ — improved mood more than mindfulness meditation.",
                BALBAN,
            )
            p(
                "Five minutes counts",
                "Breath practice doesn’t need to be long. A few minutes a day, done consistently, is enough to notice a difference.",
                "Add one 5-minute breathing session to your day, at any time.",
                "In a remote randomised trial, daily 5-minute breathing practices over one month improved mood and lowered resting breathing rate.",
                BALBAN,
            )
        },
        section("Thoughts") {
            p(
                "Don’t push thoughts away",
                "Trying hard not to think something tends to backfire. Let thoughts come and go instead of fighting them.",
                "When an unwanted thought appears, let it be there and return gently to the breath.",
                "People told not to think of a white bear couldn’t stop — and afterwards thought about it more than people never asked to suppress it.",
                "Wegner et al. · Journal of Personality and Social Psychology · 1987",
            )
            p(
                "A thought is just a thought",
                "Stepping back from a thought — seeing it as a passing mental event rather than a fact — loosens its grip.",
                "When a thought pulls, silently add ‘I’m having the thought that…’.",
                "Research on ‘decentering’ describes three linked skills: meta-awareness, stepping back from inner experience, and reduced reactivity to what thoughts say.",
                BERNSTEIN,
            )
            p(
                "Name the kind of thought",
                "A light one-word label — planning, remembering, worrying — shows you what the mind is doing without joining in.",
                "When you wake from a thought, name its type in one word, then return.",
                "Meta-awareness — knowing what your mind is doing while it does it — is one of the three core processes identified in decentering research.",
                BERNSTEIN,
            )
            p(
                "Reactions are optional",
                "You can notice a thought without acting on it. The gap between thought and action is where choice lives.",
                "Notice one ‘I should…’ thought today and let it pass without acting on it.",
                "Reduced reactivity to thought content is one of the three processes proposed to make decentering good for mental health.",
                BERNSTEIN,
            )
            p(
                "Awareness can feel louder",
                "As awareness grows, you may notice uncomfortable thoughts and feelings more at first. That is expected — pair noticing with allowing.",
                "If noticing makes something feel louder, add a slow breath and ‘this is allowed’.",
                "Monitor and Acceptance Theory proposes that monitoring on its own increases emotional reactivity, and acceptance is what brings it back down.",
                MAT,
            )
            p(
                "Less rumination",
                "Rumination — going round and round the same worry — is one of the habits meditation seems to loosen best.",
                "When a worry loops, notice ‘looping’ and come back to the breath.",
                "In a randomised trial, one month of mindfulness meditation reduced distress like relaxation training did, but seemed specifically able to reduce distracting and ruminative thoughts.",
                "Jain et al. · Annals of Behavioral Medicine · 2007",
            )
        },
        section("Body") {
            p(
                "Sense the body from inside",
                "Interoception — sensing breath, heartbeat, warmth and tension from the inside — is a core skill in meditation and a foundation of wellbeing.",
                "Scan slowly from head to feet, feeling each area from the inside.",
                "A review describes interoception — the sense of signals from inside the body — as critical for our sense of embodiment, motivation and wellbeing.",
                FARB,
            )
            p(
                "Sensation, not story",
                "Much discomfort comes from how we interpret body sensations, not only from the sensations themselves.",
                "Pick one strong sensation and describe it plainly — pressure, heat, tingling — with no story.",
                "The same review argues that misreading body sensations may underlie many modern problems, and that contemplative practice may reduce these biases.",
                FARB,
            )
            p(
                "Meeting discomfort",
                "Attending to discomfort with calm curiosity can change how much it bothers you — sometimes a lot.",
                "If discomfort arises, explore it for a few breaths before deciding whether to move.",
                "After four 20-minute training sessions, meditating during painful heat reduced pain unpleasantness by 57% and pain intensity by 40%, compared with rest.",
                ZEIDAN,
            )
            p(
                "How strong vs how bad",
                "Pain has two parts: how strong it is and how much it bothers you. Meditation tends to soften the second part most.",
                "With any discomfort today, ask separately: how strong is it? how unpleasant is it?",
                "In that study, unpleasantness fell further (57%) than intensity (40%) — the ‘bother’ is more open to change than the raw signal.",
                ZEIDAN,
            )
            p(
                "Surf the urge",
                "Urges — to move, scratch, check your phone — rise and fall. You don’t have to make them disappear; you can change what you do with them.",
                "When an urge appears, watch it for three breaths before acting.",
                "Smokers taught brief ‘urge surfing’ felt the same urges as others, yet smoked significantly fewer cigarettes over the following week.",
                BOWEN,
            )
            p(
                "Urges in daily life",
                "The same skill works off the cushion: notice the pull, feel it in the body, then choose.",
                "The next time you reach for your phone without deciding to, pause for one breath.",
                "Mindfulness didn’t reduce the urge itself; it changed the response to the urge.",
                BOWEN,
            )
        },
        section("Emotions") {
            p(
                "Name it",
                "Putting a feeling into words — anxious, sad, irritated — helps calm it. A single quiet word is enough.",
                "When a feeling arises today, name it in one word.",
                "Labelling emotions reduced the amygdala’s response to upsetting images and increased activity in a prefrontal region linked to regulation.",
                "Lieberman et al. · Psychological Science · 2007",
            )
            p(
                "Feelings live in the body",
                "Every emotion has a bodily side: a tight throat, a heavy chest, a warm face. Finding it gives you something concrete to be with.",
                "When an emotion arises, find where you feel it most.",
                "A review links interoception — sensing the body from inside — to wellbeing and to a range of emotional and psychosomatic difficulties.",
                FARB,
            )
            p(
                "Allow the feeling",
                "Resisting a feeling adds a second layer of struggle. Allowing it — without having to like it — lets it move through.",
                "With any difficult feeling today, silently say: ‘It’s okay to feel this.’",
                "In a trial that separated the ingredients, training monitoring plus acceptance reduced stress reactivity more than training monitoring alone.",
                LINDSAY_2018,
            )
            p(
                "You are not the feeling",
                "You can feel an emotion without becoming it. ‘There is anger here’ is different from ‘I am angry’.",
                "Rephrase one feeling today as ‘there is … here’.",
                "Stepping back from — disidentifying with — inner experience is one of the three processes that make up decentering.",
                BERNSTEIN,
            )
        },
        section("Open awareness") {
            p(
                "Open monitoring",
                "In open monitoring there is no single anchor. You rest in awareness of whatever arises, without reacting to it.",
                "For the last five minutes, drop the anchor and notice whatever is most vivid.",
                "Open monitoring is defined as non-reactive monitoring of the content of experience from moment to moment.",
                LUTZ,
            )
            p(
                "Focus first, then open",
                "Focused attention and open monitoring complement each other; the steadiness from focusing makes open awareness easier.",
                "Spend the first half of the sit on the breath and the second half open.",
                "The two styles train different attentional processes and may regulate attention and emotion in different ways.",
                LUTZ,
            )
            p(
                "Openness and creativity",
                "Different styles train different mental states. Open, non-grasping awareness seems to loosen thinking.",
                "Before creative work or problem-solving, try ten minutes of open monitoring.",
                "Open-monitoring meditation put people in a mental state that promotes divergent thinking — generating many new ideas.",
                "Colzato et al. · Frontiers in Psychology · 2012",
            )
        },
        section("Kindness") {
            p(
                "Loving-kindness",
                "Loving-kindness practice means silently wishing wellbeing to yourself and others. It trains warmth the way breath focus trains attention.",
                "Repeat ‘May I be well. May I be at ease.’ for a few minutes.",
                "Working adults randomised to loving-kindness practice felt more positive emotions day to day, which in turn built mindfulness, purpose, social support and fewer illness symptoms.",
                FREDRICKSON,
            )
            p(
                "Benefits build over weeks",
                "Warm feelings may not appear at first. The effects of kindness practice accumulate with time.",
                "Repeat your phrases today even if you feel nothing.",
                "The rise in daily positive emotions from loving-kindness practice grew over the weeks of the study.",
                FREDRICKSON,
            )
            p(
                "Warmth, reliably",
                "Kindness practice is one of the best-supported ways to increase positive emotion — not a vague nice idea.",
                "End today’s sit with two minutes of wishing others well.",
                "A meta-analysis concluded that loving-kindness practice and programmes are effective in enhancing positive emotions.",
                "Zeng et al. · Frontiers in Psychology · 2015",
            )
            p(
                "Minutes for strangers",
                "Even a few minutes of kindness practice can change how you feel toward people you don’t know.",
                "Picture someone you saw today but don’t know, and wish them well.",
                "A few minutes of loving-kindness increased feelings of connection and positivity toward strangers, on both conscious and implicit measures.",
                "Hutcherson et al. · Emotion · 2008",
            )
            p(
                "Compassion can be trained",
                "Compassion can be trained like attention, and structured practice reliably helps — including with low mood.",
                "Spend the last minutes of today’s sit wishing relief to someone who is struggling.",
                "A meta-analysis of 21 randomised trials (1,285 people) found compassion-based programmes had a moderate effect on depression.",
                "Kirby et al. · Behavior Therapy · 2017",
            )
            p(
                "Soothing touch",
                "Self-compassion includes simple gestures: a hand on the heart, a warm tone toward your own breathing.",
                "At a hard moment today, place a hand on your chest and breathe kindly.",
                "An 8-week Mindful Self-Compassion course — including affectionate breathing and soothing touch — increased self-compassion, mindfulness and wellbeing compared with a waitlist.",
                "Neff & Germer · Journal of Clinical Psychology · 2013",
            )
            p(
                "Gratitude",
                "Regularly noticing what you’re grateful for shifts attention toward what is going well.",
                "Before the opening bell, recall three things you are grateful for.",
                "People who kept gratitude lists showed higher wellbeing on several measures than comparison groups — most reliably, more positive mood.",
                "Emmons & McCullough · Journal of Personality and Social Psychology · 2003",
            )
            p(
                "Savour good moments",
                "Deliberately staying with a good moment — noticing it, feeling it, letting it last — amplifies its effect.",
                "If a pleasant moment appears today, stay with it for three breaths.",
                "In a 30-day study of 101 people, savouring strengthened the link between daily positive events and happy mood.",
                "Jose et al. · Journal of Positive Psychology · 2012",
            )
        },
        section("Daily life") {
            p(
                "Eat attentively",
                "Eating while distracted changes not only what you taste but how much you eat later.",
                "Eat the first few bites of your next meal with no screen.",
                "Across 24 studies, distracted eating increased how much people ate, especially at later meals; remembering a meal clearly reduced later intake.",
                "Robinson et al. · American Journal of Clinical Nutrition · 2013",
            )
            p(
                "Mindful walking",
                "Meditation can move. Walking with attention on each step brings the same training into ordinary life.",
                "Walk for a few minutes today feeling each step: lifting, moving, placing.",
                "Distressed adults who did eight mindful-walking sessions over four weeks had lower stress and better quality of life than a waitlist group.",
                "Teut et al. · Evidence-Based Complementary and Alternative Medicine · 2013",
            )
            p(
                "Walk among trees",
                "Where you walk matters too. Time in nature quiets the mind’s tendency to loop on negatives.",
                "If you can, take today’s walk somewhere green.",
                "A 90-minute walk in nature — but not in the city — reduced rumination and activity in a brain area linked to risk for mental illness.",
                "Bratman et al. · PNAS · 2015",
            )
            p(
                "Sleep and practice",
                "Meditation can help sleep, but it isn’t a cure-all. It seems to help more than simply learning about sleep, not more than other active treatments.",
                "If you sit in the evening, make it a gentle, unhurried practice.",
                "A meta-analysis of 18 trials (1,654 people) found mindfulness improved sleep quality more than education-based approaches, but not more than specific active treatments.",
                "Rusch et al. · Annals of the New York Academy of Sciences · 2019",
            )
            p(
                "One task, fully",
                "Happiness tracks where attention is. Doing one ordinary thing with full attention is meditation in daily life.",
                "Choose one routine task today and do it with complete attention.",
                "People were generally happier when their minds were on what they were doing than when their minds wandered elsewhere.",
                KILLINGSWORTH,
            )
        },
    ).flatten()
}
