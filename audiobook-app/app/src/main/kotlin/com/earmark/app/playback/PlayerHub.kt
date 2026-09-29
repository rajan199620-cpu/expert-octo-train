package com.earmark.app.playback

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import com.earmark.app.data.AppSettings
import com.earmark.app.data.Library
import com.earmark.app.data.NarratorEngine
import com.earmark.app.data.SettingsStore
import com.earmark.app.voice.VoiceInput
import com.earmark.core.annotations.AnnotationSource
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.assistant.Assistant
import com.earmark.core.assistant.AssistantReply
import com.earmark.core.commands.Command
import com.earmark.core.commands.CommandParser
import com.earmark.core.model.Book
import com.earmark.core.player.PlaybackController
import com.earmark.core.player.PlaybackStatus
import com.earmark.core.player.PlayerState
import com.earmark.core.player.WordTracker
import com.earmark.core.qa.ClaudeAnswerer
import com.earmark.core.qa.ExtractiveAnswerer
import com.earmark.core.qa.FallbackAnswerer
import com.earmark.core.qa.PassageIndex
import com.earmark.core.qa.QaPromptBuilder
import com.earmark.core.text.Lexicon
import com.earmark.core.text.ScriptDetector
import com.earmark.core.text.SpeechNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The word being spoken: display character range within sentence [sentence]. */
data class WordHighlight(val sentence: Int, val range: IntRange)

/** What the assistant overlay shows. */
sealed interface AssistantUi {
    data object Idle : AssistantUi
    data class Listening(val partial: String) : AssistantUi
    data class Thinking(val heard: String) : AssistantUi
    data class Replied(val heard: String, val reply: AssistantReply, val isAnswer: Boolean) : AssistantUi
    data class Error(val message: String) : AssistantUi
}

class Session(
    val book: Book,
    val controller: PlaybackController,
    val engine: AppEngine,
    val annotations: AnnotationStore,
    val assistant: Assistant,
    private val closeables: List<AutoCloseable>,
) {
    fun close() {
        runCatching { engine.close() }
        closeables.forEach { runCatching { it.close() } }
    }
}

/**
 * Owns the open book and everything that plays it. UI, notification and headset all talk to
 * this one object; it lives as long as the process.
 */
class PlayerHub(private val app: Application, val library: Library, val settingsStore: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val voiceInput = VoiceInput(app)
    private var replySpeaker: ReplySpeaker? = null

    private val sessionState = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = sessionState.asStateFlow()

    private val playerStateFlow = MutableStateFlow<PlayerState?>(null)
    val playerState: StateFlow<PlayerState?> = playerStateFlow.asStateFlow()

    private val assistantState = MutableStateFlow<AssistantUi>(AssistantUi.Idle)
    val assistant: StateFlow<AssistantUi> = assistantState.asStateFlow()

    private val wordFlow = MutableStateFlow<WordHighlight?>(null)
    /** Word-level progress, kept apart from [playerState] so the notification isn't rebuilt per word. */
    val currentWord: StateFlow<WordHighlight?> = wordFlow.asStateFlow()

    /** Bumped whenever annotations change so the reader redraws highlights. */
    private val annotationVersionFlow = MutableStateFlow(0)
    val annotationVersion: StateFlow<Int> = annotationVersionFlow.asStateFlow()

    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var lastSavedPosition = -1

    // ---- Opening books ---------------------------------------------------------------

    fun open(bookId: String, autoplay: Boolean = false) {
        val current = sessionState.value
        if (current?.book?.id == bookId) {
            if (autoplay) play()
            return
        }
        scope.launch {
            val book = withContext(Dispatchers.IO) { library.loadBook(bookId) }
            if (book == null) {
                assistantState.value = AssistantUi.Error("That book's data is missing. Import it again.")
                return@launch
            }
            startSession(book, library.positionOf(bookId))
            if (autoplay) play()
        }
    }

    /** Rebuilds the session (e.g. after the voice or pronunciation settings change), keeping position. */
    fun reloadEngine() {
        val s = sessionState.value ?: return
        val wasPlaying = s.controller.state.status == PlaybackStatus.PLAYING
        val position = s.controller.state.position
        startSession(s.book, position)
        if (wasPlaying) play()
    }

    fun close() {
        pause()
        sessionState.value?.close()
        sessionState.value = null
        playerStateFlow.value = null
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun startSession(book: Book, position: Int) {
        sessionState.value?.let { old ->
            old.controller.pause()
            old.close()
        }
        val settings = settingsStore.current
        var controllerRef: PlaybackController? = null
        val callbacks = object : EngineCallbacks {
            override fun started(id: String) { controllerRef?.onUtteranceStarted(id) }
            override fun done(id: String) { controllerRef?.onUtteranceDone(id) }
            override fun error(id: String, message: String) {
                controllerRef?.onUtteranceError(id)
                scope.launch { assistantState.value = AssistantUi.Error(message) }
            }
            override fun range(id: String, start: Int, end: Int) { controllerRef?.onUtteranceRange(id, start, end) }
        }
        val (engine, lookahead) = createEngine(book, settings, callbacks)
        val normalizer = SpeechNormalizer(Lexicon(settings.lexicon))
        val controller = PlaybackController(book, engine, normalizer, lookahead = lookahead, startPosition = position)
        controllerRef = controller
        controller.setSpeed(settings.speed)
        engine.setSpeed(settings.speed)

        val annotations = library.annotationStore(book)
        val previousOnChange = annotations.onChange
        annotations.onChange = {
            previousOnChange?.invoke()
            annotationVersionFlow.value++
        }
        val index = PassageIndex(book)
        val claude = settings.anthropicKey.trim().takeIf { it.isNotEmpty() }?.let {
            ClaudeAnswerer(book, it, QaPromptBuilder(book, index), fullBookContext = settings.fullBookContext)
        }
        val answerer = FallbackAnswerer(claude, ExtractiveAnswerer(book, index))
        val assistant = Assistant(book, controller, annotations, answerer) { settingsStore.current.spoilerSafe }

        controller.listener = { state -> onPlayerState(book, state) }
        val words = WordTracker(book) { controller.speechTextFor(it) }
        controller.rangeListener = { sentence, start, _ ->
            words.displayRange(sentence, start)?.let { wordFlow.value = WordHighlight(sentence, it) }
        }
        wordFlow.value = null
        val session = Session(book, controller, engine, annotations, assistant, listOfNotNull(claude))
        sessionState.value = session
        playerStateFlow.value = controller.state
        annotationVersionFlow.value++
        if (book.warnings.isNotEmpty()) assistantState.value = AssistantUi.Error(book.warnings.joinToString("\n"))
    }

    private fun createEngine(book: Book, s: AppSettings, callbacks: EngineCallbacks): Pair<AppEngine, Int> {
        if (s.engine != NarratorEngine.SYSTEM) {
            val cloud = cloudVoiceFor(app, s)
            if (cloud != null) return CloudTtsEngine(app, cloud.first, cloud.second, callbacks) to 12
            val provider = if (s.engine == NarratorEngine.ELEVENLABS) "ElevenLabs" else "OpenAI"
            assistantState.value = AssistantUi.Error("Add your $provider API key in Settings. Using the on-device voice for now.")
        }
        return SystemTtsEngine(app, s.systemVoiceName, ScriptDetector.guessLanguage(book), s.performReading, callbacks) to 3
    }

    private fun onPlayerState(book: Book, state: PlayerState) {
        playerStateFlow.value = state
        val shouldSave = state.status != PlaybackStatus.PLAYING || kotlin.math.abs(state.position - lastSavedPosition) >= 5
        if (shouldSave && state.position != lastSavedPosition) {
            lastSavedPosition = state.position
            scope.launch(Dispatchers.IO) { library.savePosition(book.id, state.position) }
        }
    }

    // ---- Transport ------------------------------------------------------------------

    fun play() {
        val s = sessionState.value ?: return
        if (!requestFocus()) {
            assistantState.value = AssistantUi.Error("Another app is using audio right now.")
            return
        }
        registerNoisy()
        startService()
        s.controller.play()
    }

    fun pause() {
        resumeOnFocusGain = false
        sessionState.value?.controller?.pause()
        unregisterNoisy()
    }

    fun togglePlayPause() {
        if (playerStateFlow.value?.status == PlaybackStatus.PLAYING) pause() else play()
    }

    fun seekTo(index: Int) = sessionState.value?.controller?.seekTo(index)
    fun skipSentences(delta: Int) = sessionState.value?.controller?.skipSentences(delta)
    fun rewindSeconds(seconds: Int) = sessionState.value?.controller?.rewindSeconds(seconds)
    fun forwardSeconds(seconds: Int) = sessionState.value?.controller?.forwardSeconds(seconds)
    fun goToChapter(number: Int) = sessionState.value?.controller?.goToChapter(number)

    fun setSpeed(speed: Float) {
        val c = sessionState.value?.controller ?: return
        c.setSpeed(speed)
        settingsStore.update { it.copy(speed = c.state.speed) }
    }

    fun setSleepTimer(minutes: Int?) {
        val c = sessionState.value?.controller ?: return
        when {
            minutes == null -> c.cancelSleepTimer()
            minutes < 0 -> c.sleepAtEndOfChapter()
            else -> c.setSleepTimer(minutes)
        }
    }

    // ---- Annotations from touch / headset --------------------------------------------

    fun bookmark(sentence: Int, scope: BookmarkScope = BookmarkScope.SENTENCE, source: AnnotationSource = AnnotationSource.TOUCH): Boolean {
        val s = sessionState.value ?: return false
        return s.annotations.bookmark(s.book, sentence, scope, source = source).created
    }

    fun highlight(from: Int, to: Int, color: HighlightColor) {
        val s = sessionState.value ?: return
        s.annotations.highlight(s.book, from, to, color)
    }

    fun addNote(sentence: Int, text: String) {
        val s = sessionState.value ?: return
        if (text.isNotBlank()) s.annotations.addNote(s.book, sentence, text)
    }

    fun removeAnnotation(id: String) {
        sessionState.value?.annotations?.remove(id)
    }

    /**
     * Headset "next" / double-tap: bookmark what was just heard without stopping or speaking,
     * confirmed with a short tone (the Snipd trick). Falls back to "next sentence" if disabled.
     */
    fun headsetNext() {
        val s = sessionState.value ?: return
        if (!settingsStore.current.headsetNextBookmarks) {
            s.controller.skipSentences(1)
            return
        }
        val anchor = s.controller.anchorForInteraction()
        val created = s.annotations.bookmark(s.book, anchor, source = AnnotationSource.HEADSET).created
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(if (created) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 180)
            scope.launch { kotlinx.coroutines.delay(400); tone.release() }
        }
    }

    // ---- Voice & typed assistant ---------------------------------------------------------

    /** Push-to-talk pressed: remember what was being heard, pause, listen. */
    fun startVoiceCommand() {
        val s = sessionState.value ?: return
        val wasPlaying = s.controller.state.status == PlaybackStatus.PLAYING
        val anchor = s.controller.anchorForInteraction()
        s.controller.pause()
        replySpeaker?.stop()
        assistantState.value = AssistantUi.Listening("")
        voiceInput.start(object : VoiceInput.Listener {
            override fun onPartial(text: String) {
                assistantState.value = AssistantUi.Listening(text)
            }

            override fun onResult(alternatives: List<String>) {
                execute(pickCommand(alternatives), alternatives.first(), anchor, wasPlaying)
            }

            override fun onFailure(message: String) {
                assistantState.value = AssistantUi.Error(message)
                if (wasPlaying) play()
            }
        })
    }

    fun stopListening() = voiceInput.stopListening()

    fun cancelVoiceCommand() {
        voiceInput.cancel()
        assistantState.value = AssistantUi.Idle
    }

    /** Typed question or command (for when talking out loud isn't an option). */
    fun submitText(text: String) {
        val s = sessionState.value ?: return
        val wasPlaying = s.controller.state.status == PlaybackStatus.PLAYING
        val anchor = s.controller.anchorForInteraction()
        s.controller.pause()
        execute(CommandParser.parse(text), text, anchor, wasPlaying)
    }

    fun explainSentence(index: Int) {
        val s = sessionState.value ?: return
        val wasPlaying = s.controller.state.status == PlaybackStatus.PLAYING
        s.controller.pause()
        execute(Command.Explain(0), "Explain this sentence", index, wasPlaying)
    }

    fun dismissAssistant() {
        replySpeaker?.stop()
        assistantState.value = AssistantUi.Idle
    }

    private fun execute(command: Command, heard: String, anchor: Int, wasPlaying: Boolean) {
        val s = sessionState.value ?: return
        val isAnswer = command is Command.Ask || command is Command.Define || command is Command.Explain ||
            command is Command.Recap || command is Command.ListAnnotations || command is Command.Help || command is Command.WhereAmI
        assistantState.value = if (isAnswer) AssistantUi.Thinking(heard) else AssistantUi.Listening(heard)
        scope.launch {
            val reply = withContext(Dispatchers.IO) { s.assistant.handle(command, anchor, wasPlaying) }
            if (sessionState.value !== s) return@launch
            assistantState.value = AssistantUi.Replied(heard, reply, isAnswer)
            val speakIt = isAnswer || settingsStore.current.spokenConfirmations
            val speaker = replySpeaker ?: ReplySpeaker(app).also { replySpeaker = it }
            speaker.speak(if (speakIt) reply.speech else "") {
                if (reply.resume && sessionState.value === s) play()
                if (!isAnswer && assistantState.value is AssistantUi.Replied) assistantState.value = AssistantUi.Idle
            }
        }
    }

    /**
     * Recognisers return several guesses. Prefer one that is a concrete command when the top
     * guess is unclear, e.g. ["book mark this", "bookmark this"].
     */
    private fun pickCommand(alternatives: List<String>): Command {
        val parsed = alternatives.map(CommandParser::parse)
        val top = parsed.first()
        val topIsVague = top is Command.Unknown || (top is Command.Ask && alternatives.first().split(' ').size < 4)
        if (!topIsVague) return top
        return parsed.firstOrNull { it !is Command.Ask && it !is Command.Unknown } ?: top
    }

    // ---- Audio focus, noisy, service -------------------------------------------------

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Speech under a navigation prompt is unintelligible: pause, then resume.
                        val playing = playerStateFlow.value?.status == PlaybackStatus.PLAYING
                        sessionState.value?.controller?.pause()
                        resumeOnFocusGain = playing
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        sessionState.value?.controller?.play()
                    }
                    else -> Unit
                }
            }
            .build()
    }

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() // headphones unplugged
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
    }
}
