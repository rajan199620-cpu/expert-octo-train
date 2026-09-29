package com.earmark.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earmark.app.data.ReaderTheme
import com.earmark.app.playback.AssistantUi
import com.earmark.app.playback.PlayerHub
import com.earmark.core.annotations.AnnotationKind
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.model.Book
import com.earmark.core.player.PlaybackStatus

private val SPEEDS = listOf(0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

fun highlightColor(c: HighlightColor): Color = when (c) {
    HighlightColor.YELLOW -> Color(0x80FFD54F)
    HighlightColor.GREEN -> Color(0x7081C784)
    HighlightColor.BLUE -> Color(0x7064B5F6)
    HighlightColor.PINK -> Color(0x70F48FB1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(hub: PlayerHub, onBack: () -> Unit, onAnnotations: () -> Unit, onSettings: () -> Unit) {
    val session by hub.session.collectAsStateWithLifecycle()
    val state by hub.playerState.collectAsStateWithLifecycle()
    val assistant by hub.assistant.collectAsStateWithLifecycle()
    val version by hub.annotationVersion.collectAsStateWithLifecycle()
    val settings by hub.settingsStore.settings.collectAsStateWithLifecycle()
    val palette = readerPalette(settings.readerTheme, isSystemInDarkTheme())
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.accent)
        }
        return
    }
    val book = s.book
    val context = LocalContext.current

    val paragraphs = remember(book) { paragraphRanges(book) }
    val itemOfSentence = remember(book) {
        IntArray(book.sentences.size).also { arr -> paragraphs.forEachIndexed { i, r -> for (k in r) arr[k] = i } }
    }
    val charsBefore = remember(book) {
        LongArray(book.sentences.size + 1).also { a -> for (i in book.sentences.indices) a[i + 1] = a[i] + book.sentences[i].text.length }
    }
    val annotations = remember(version, book) { s.annotations.forBook(book.id).filter { !it.orphaned } }
    val highlightOf = remember(annotations) {
        HashMap<Int, HighlightColor>().also { m -> annotations.filter { it.kind == AnnotationKind.HIGHLIGHT }.forEach { a -> for (i in a.range) m[i] = a.color } }
    }
    val bookmarked = remember(annotations) { annotations.filter { it.kind == AnnotationKind.BOOKMARK }.map { it.start.sentenceIndex }.toSet() }
    val noted = remember(annotations) { annotations.filter { it.kind == AnnotationKind.NOTE }.map { it.start.sentenceIndex }.toSet() }

    val position = state?.position ?: 0
    val playing = state?.status == PlaybackStatus.PLAYING
    val speed = state?.speed ?: 1f
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(position, follow) {
        if (!follow || book.isEmpty) return@LaunchedEffect
        val item = itemOfSentence[book.clampIndex(position)]
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (item !in visible.dropLast(1)) listState.animateScrollToItem(maxOf(0, item - 1))
    }

    var menuFor by remember { mutableStateOf<Int?>(null) }
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var askOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) hub.startVoiceCommand()
    }
    fun onMic() {
        if (assistant is AssistantUi.Listening) { hub.stopListening(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) hub.startVoiceCommand()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val chapter = book.chapterOf(position)
    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.background,
                    titleContentColor = palette.text,
                    navigationIconContentColor = palette.text,
                    actionIconContentColor = palette.text,
                ),
                title = {
                    Column {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontFamily = SerifFamily)
                        chapter?.let {
                            Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = palette.readText)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Library") } },
                actions = {
                    IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Default.FormatSize, "Text and theme") }
                    Box {
                        IconButton(onClick = { chaptersOpen = true }) { Icon(Icons.AutoMirrored.Filled.List, "Chapters") }
                        DropdownMenu(expanded = chaptersOpen, onDismissRequest = { chaptersOpen = false }) {
                            book.chapters.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.index + 1}. ${c.title}", fontWeight = if (c.index == chapter?.index) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { chaptersOpen = false; follow = true; hub.goToChapter(c.index + 1) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onAnnotations) { Icon(Icons.Default.Bookmarks, "Bookmarks and highlights") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NowPlayingSheet(
                book = book,
                position = position,
                playing = playing,
                speed = speed,
                charsBefore = charsBefore,
                sleepDeadline = state?.sleepDeadlineMillis,
                sleepAtChapterEnd = state?.sleepAtChapterEnd == true,
                listening = assistant is AssistantUi.Listening,
                bookmarkedHere = position in bookmarked,
                actions = remember(hub) { HubActions(hub) },
                onFollow = { follow = true },
                onMic = { onMic() },
                onAsk = { askOpen = true },
                onBookmark = { hub.bookmark(s.controller.anchorForInteraction()) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(palette.background)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 180.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(paragraphs, key = { _, r -> r.first }) { _, range ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        ParagraphText(
                            book = book,
                            range = range,
                            current = position,
                            highlights = highlightOf,
                            bookmarked = bookmarked,
                            noted = noted,
                            palette = palette,
                            fontScale = settings.fontScale,
                            onTap = { idx -> follow = true; hub.seekTo(idx) },
                            onLongPress = { idx -> menuFor = idx },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = !follow,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                AssistChip(
                    onClick = { follow = true },
                    label = { Text("Back to the voice") },
                    leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            AnimatedVisibility(
                visible = assistant !is AssistantUi.Idle,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                AssistantOverlay(
                    ui = assistant,
                    book = book,
                    onDismiss = { hub.dismissAssistant() },
                    onStopListening = { hub.stopListening() },
                    onCancelListening = { hub.cancelVoiceCommand() },
                    onJump = { idx -> follow = true; hub.seekTo(idx) },
                )
            }
        }
    }

    menuFor?.let { idx ->
        val here = annotations.filter { idx in it.range }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } },
            title = { Text("“${book.sentences[idx].text}”", maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontFamily = SerifFamily) },
            text = {
                Column {
                    MenuRow("Play from here") { menuFor = null; follow = true; hub.seekTo(idx); if (!playing) hub.play() }
                    MenuRow("Bookmark") { menuFor = null; hub.bookmark(idx) }
                    SectionLabel("Highlight", Modifier.padding(top = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                        HighlightColor.values().forEach { c ->
                            Box(
                                Modifier.size(38.dp).background(highlightColor(c).copy(alpha = 1f), CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                    .clickable { menuFor = null; hub.highlight(idx, idx, c) },
                            )
                        }
                    }
                    MenuRow("Add a note…") { menuFor = null; noteFor = idx }
                    MenuRow("Explain this sentence") { menuFor = null; hub.explainSentence(idx) }
                    if (here.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        here.forEach { a ->
                            MenuRow("Remove ${AnnotationStore.describe(a)}") { menuFor = null; hub.removeAnnotation(a.id) }
                        }
                    }
                }
            },
        )
    }

    noteFor?.let { idx ->
        var text by remember(idx) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            confirmButton = { TextButton(onClick = { hub.addNote(idx, text); noteFor = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Cancel") } },
            title = { Text("Note") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Your thoughts on this passage") }, minLines = 3) },
        )
    }

    if (askOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askOpen = false },
            confirmButton = { TextButton(onClick = { askOpen = false; hub.submitText(text) }, enabled = text.isNotBlank()) { Text("Ask") } },
            dismissButton = { TextButton(onClick = { askOpen = false }) { Text("Cancel") } },
            title = { Text("Ask the book") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Who is Tobin? · bookmark this · go to page 40") },
                    minLines = 2,
                )
            },
        )
    }

    if (appearanceOpen) {
        AlertDialog(
            onDismissRequest = { appearanceOpen = false },
            confirmButton = { TextButton(onClick = { appearanceOpen = false }) { Text("Done") } },
            title = { Text("Reading appearance") },
            text = {
                Column {
                    SectionLabel("Page")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        ReaderTheme.values().forEach { t ->
                            val p = readerPalette(t, isSystemInDarkTheme())
                            val selected = settings.readerTheme == t
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { hub.settingsStore.update { it.copy(readerTheme = t) } }) {
                                Box(
                                    Modifier.size(52.dp).background(p.background, CircleShape)
                                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text("Aa", color = p.text, fontFamily = SerifFamily, fontSize = 18.sp) }
                                Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    SectionLabel("Text size")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", fontFamily = SerifFamily, fontSize = 14.sp)
                        Slider(
                            value = settings.fontScale,
                            onValueChange = { v -> hub.settingsStore.update { it.copy(fontScale = v) } },
                            valueRange = 0.8f..1.6f,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text("A", fontFamily = SerifFamily, fontSize = 24.sp)
                    }
                    Text(
                        "The voice follows the text; tap any sentence to jump.",
                        style = readingStyle(settings.fontScale).copy(color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            },
        )
    }
}

/** What the now-playing sheet can do; the hub in the app, a no-op in screenshot tests. */
interface PlayerActions {
    fun seekTo(index: Int) {}
    fun skipSentences(delta: Int) {}
    fun rewindSeconds(seconds: Int) {}
    fun forwardSeconds(seconds: Int) {}
    fun togglePlayPause() {}
    fun setSpeed(speed: Float) {}
    fun setSleepTimer(minutes: Int?) {}
    fun whereAmI() {}
}

private class HubActions(private val hub: PlayerHub) : PlayerActions {
    override fun seekTo(index: Int) { hub.seekTo(index) }
    override fun skipSentences(delta: Int) { hub.skipSentences(delta) }
    override fun rewindSeconds(seconds: Int) { hub.rewindSeconds(seconds) }
    override fun forwardSeconds(seconds: Int) { hub.forwardSeconds(seconds) }
    override fun togglePlayPause() = hub.togglePlayPause()
    override fun setSpeed(speed: Float) = hub.setSpeed(speed)
    override fun setSleepTimer(minutes: Int?) = hub.setSleepTimer(minutes)
    override fun whereAmI() = hub.submitText("where am I")
}

@Composable
internal fun NowPlayingSheet(
    book: Book,
    position: Int,
    playing: Boolean,
    speed: Float,
    charsBefore: LongArray,
    sleepDeadline: Long?,
    sleepAtChapterEnd: Boolean,
    listening: Boolean,
    bookmarkedHere: Boolean,
    actions: PlayerActions,
    onFollow: () -> Unit,
    onMic: () -> Unit,
    onAsk: () -> Unit,
    onBookmark: () -> Unit,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val total = book.sentences.size
    val shown = scrub?.toInt() ?: position
    val chapter = book.chapterOf(shown)
    val minutesLeft = if (book.isEmpty) 0 else ((charsBefore[total] - charsBefore[book.clampIndex(shown)]) / 15.0 / 60.0 / speed).toInt()
    val bookmarkScale by animateFloatAsState(if (bookmarkedHere) 1.15f else 1f, label = "bookmark")

    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EqualizerBars(playing, MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Text(
                    chapter?.title ?: book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    AssistChip(
                        onClick = { speedOpen = true },
                        label = { Text(speedLabel(speed), fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(50),
                    )
                    DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                        SPEEDS.forEach { v -> DropdownMenuItem(text = { Text(speedLabel(v)) }, onClick = { speedOpen = false; actions.setSpeed(v) }) }
                    }
                }
            }
            if (total > 1) {
                Slider(
                    value = scrub ?: position.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { actions.seekTo(it.toInt()) }; scrub = null; onFollow() },
                    valueRange = 0f..(total - 1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                val pageLabel = if (book.pagesAreVirtual) "≈ p." else "p."
                Text(
                    "$pageLabel ${book.sentences.getOrNull(shown)?.page ?: 1} / ${book.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatDuration(minutesLeft)} left" + sleepLabel(sleepDeadline, sleepAtChapterEnd),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.rewindSeconds(10) }) { Icon(Icons.Default.Replay10, "Back 10 seconds", Modifier.size(28.dp)) }
                IconButton(onClick = { actions.skipSentences(-1) }) { Icon(Icons.Default.SkipPrevious, "Previous sentence", Modifier.size(28.dp)) }
                FilledIconButton(
                    onClick = { onFollow(); actions.togglePlayPause() },
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(38.dp))
                }
                IconButton(onClick = { actions.skipSentences(1) }) { Icon(Icons.Default.SkipNext, "Next sentence", Modifier.size(28.dp)) }
                IconButton(onClick = { actions.forwardSeconds(30) }) { Icon(Icons.Default.Forward30, "Forward 30 seconds", Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBookmark, modifier = Modifier.pop(bookmarkScale)) {
                    Icon(
                        if (bookmarkedHere) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Bookmark what you just heard",
                        tint = if (bookmarkedHere) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAsk) { Icon(Icons.Default.Keyboard, "Type a question", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                    PulseRings(listening, MaterialTheme.colorScheme.secondary, Modifier.size(84.dp))
                    Box(
                        Modifier.size(60.dp)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)), CircleShape)
                            .clickable(onClick = onMic),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (listening) Icons.Default.Close else Icons.Default.Mic, "Talk to the book", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Box {
                    IconButton(onClick = { sleepOpen = true }) {
                        Icon(
                            Icons.Default.Bedtime,
                            "Sleep timer",
                            tint = if (sleepDeadline != null || sleepAtChapterEnd) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = sleepOpen, onDismissRequest = { sleepOpen = false }) {
                        listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                            DropdownMenuItem(text = { Text("$m minutes") }, onClick = { sleepOpen = false; actions.setSleepTimer(m) })
                        }
                        DropdownMenuItem(text = { Text("End of chapter") }, onClick = { sleepOpen = false; actions.setSleepTimer(-1) })
                        DropdownMenuItem(text = { Text("Off") }, onClick = { sleepOpen = false; actions.setSleepTimer(null) })
                    }
                }
                IconButton(onClick = { actions.whereAmI() }) {
                    Icon(Icons.Default.AutoAwesome, "Where am I?", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
internal fun ParagraphText(
    book: Book,
    range: IntRange,
    current: Int,
    highlights: Map<Int, HighlightColor>,
    bookmarked: Set<Int>,
    noted: Set<Int>,
    palette: ReaderPalette,
    fontScale: Float,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val isHeading = book.sentences[range.first].isHeading
    val starts = IntArray(range.last - range.first + 1)
    val text: AnnotatedString = buildAnnotatedString {
        for (i in range) {
            starts[i - range.first] = length
            if (i in bookmarked) withStyle(SpanStyle(color = palette.accent)) { append("❝ ") }
            val span = when {
                i == current -> SpanStyle(background = palette.currentBackground, color = palette.currentText)
                i < current -> SpanStyle(color = palette.readText)
                else -> SpanStyle(color = if (isHeading) palette.heading else palette.text)
            }
            val style = highlights[i]?.let { c -> if (i == current) span else span.merge(SpanStyle(background = highlightColor(c))) } ?: span
            withStyle(style) { append(book.sentences[i].text) }
            if (i in noted) withStyle(SpanStyle(color = palette.accent)) { append(" ✎") }
            if (i < range.last) append(' ')
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentStarts by rememberUpdatedState(starts)
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    fun sentenceAt(offset: Int): Int {
        val st = currentStarts
        var k = 0
        while (k + 1 < st.size && st[k + 1] <= offset) k++
        return range.first + k
    }
    val base = readingStyle(fontScale)
    Text(
        text = text,
        style = if (isHeading) base.copy(fontSize = base.fontSize * 1.35f, lineHeight = base.lineHeight * 1.2f, fontWeight = FontWeight.SemiBold) else base,
        onTextLayout = { layout = it },
        modifier = Modifier
            .widthIn(max = 680.dp) // comfortable line length on tablets
            .fillMaxWidth()
            .padding(top = if (isHeading) 28.dp else 6.dp, bottom = if (isHeading) 12.dp else 10.dp)
            .pointerInput(range) {
                detectTapGestures(
                    onTap = { pos -> layout?.let { tap(sentenceAt(it.getOffsetForPosition(pos))) } },
                    onLongPress = { pos -> layout?.let { longPress(sentenceAt(it.getOffsetForPosition(pos))) } },
                )
            },
    )
}

@Composable
internal fun AssistantOverlay(
    ui: AssistantUi,
    book: Book,
    onDismiss: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onJump: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    when (ui) {
                        is AssistantUi.Listening -> "Listening"
                        is AssistantUi.Thinking -> "Thinking"
                        is AssistantUi.Error -> "Heads up"
                        else -> "Earmark"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (ui !is AssistantUi.Listening) IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            when (ui) {
                is AssistantUi.Listening -> {
                    ListeningWave(MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        ui.partial.ifEmpty { "Try “bookmark this”, “highlight that” or “who is Tobin?”" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ui.partial.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Row {
                        TextButton(onClick = onStopListening) { Text("Done") }
                        TextButton(onClick = onCancelListening) { Text("Cancel") }
                    }
                }
                is AssistantUi.Thinking -> {
                    Text("“${ui.heard}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    ListeningWave(MaterialTheme.colorScheme.tertiary, Modifier.height(16.dp))
                }
                is AssistantUi.Replied -> {
                    Text("“${ui.heard}”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(ui.reply.speech.ifEmpty { "Done." }, style = readingStyle(0.9f).copy(color = MaterialTheme.colorScheme.onSurface))
                    if (ui.reply.sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.reply.sources.take(3).forEach { idx ->
                                val page = book.sentences.getOrNull(idx)?.page
                                AssistChip(onClick = { onJump(idx) }, label = { Text("Page ${page ?: "?"}") }, leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(16.dp)) })
                            }
                        }
                    }
                }
                is AssistantUi.Error -> Text(ui.message, style = MaterialTheme.typography.bodyMedium)
                AssistantUi.Idle -> Unit
            }
        }
    }
}

internal fun paragraphRanges(book: Book): List<IntRange> {
    val out = ArrayList<IntRange>()
    var start = 0
    for (i in 1..book.sentences.size) {
        if (i == book.sentences.size || book.sentences[i].paragraph != book.sentences[start].paragraph) {
            if (start < book.sentences.size) out += start until i
            start = i
        }
    }
    return out
}

private fun speedLabel(v: Float): String {
    val s = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else v.toString().trimEnd('0')
    return "$s×"
}

private fun formatDuration(minutes: Int): String = when {
    minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
    else -> "$minutes min"
}

private fun sleepLabel(deadline: Long?, atChapterEnd: Boolean): String = when {
    atChapterEnd -> " · sleeps at chapter end"
    deadline != null -> " · sleeps in ${maxOf(0, (deadline - System.currentTimeMillis()) / 60_000)} min"
    else -> ""
}
