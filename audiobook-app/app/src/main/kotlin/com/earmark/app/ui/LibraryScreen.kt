package com.earmark.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earmark.app.data.ImportResult
import com.earmark.app.data.Library
import com.earmark.core.model.BookSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

private val OPENABLE_TYPES = arrayOf(
    "application/pdf",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/*",
    "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(library: Library, incoming: MutableStateFlow<Uri?>, onOpen: (String, Boolean) -> Unit, onSettings: () -> Unit) {
    val books by library.books.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf<BookSummary?>(null) }

    fun importUri(uri: Uri) {
        scope.launch {
            progress = "Opening…"
            val result = library.importDocument(uri) { p -> scope.launch { progress = p } }
            progress = null
            when (result) {
                is ImportResult.Failed -> message = "Couldn't import" to result.message
                is ImportResult.Imported -> {
                    if (result.warnings.isNotEmpty()) message = "Imported with warnings" to result.warnings.joinToString("\n")
                    onOpen(result.book.id, false)
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importUri(uri) }
    val shared by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let { uri ->
            incoming.value = null
            importUri(uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Earmark", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (progress == null) picker.launch(OPENABLE_TYPES) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add a book") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            progress?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (books.isEmpty() && progress == null) {
                EmptyLibrary(onAdd = { picker.launch(OPENABLE_TYPES) })
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                books.firstOrNull()?.let { recent ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                        ContinueListening(recent, onResume = { onOpen(recent.id, true) }, onOpen = { onOpen(recent.id, false) })
                    }
                }
                if (books.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "label") { SectionLabel("Your library", Modifier.padding(top = 8.dp)) }
                    items(books.drop(1), key = { it.id }) { b ->
                        CoverTile(b, onClick = { onOpen(b.id, false) }, onLongClick = { confirmDelete = b })
                    }
                }
            }
        }
    }

    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text(title) },
            text = { Text(body) },
        )
    }
    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { library.delete(b.id); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Remove \"${b.title}\"?") },
            text = { Text("Its bookmarks, highlights and notes are removed too. Export them first if you want to keep them.") },
        )
    }
}

@Composable
internal fun ContinueListening(book: BookSummary, onResume: () -> Unit, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, book.author, Modifier.width(96.dp).aspectRatio(0.68f), compact = true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel(if (book.position > 0) "Continue listening" else "Start listening")
                Spacer(Modifier.height(4.dp))
                Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                Spacer(Modifier.height(12.dp))
                ProgressLine(book.progress)
                Spacer(Modifier.height(6.dp))
                Text("${(book.progress * 100).toInt()}% · ${book.chapterCount} chapters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(
                onClick = onResume,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Icon(Icons.Default.PlayArrow, contentDescription = "Resume", Modifier.size(30.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(book: BookSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        BookCover(book.title, book.author, Modifier.fillMaxWidth().aspectRatio(0.68f))
        Spacer(Modifier.height(8.dp))
        ProgressLine(book.progress)
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(book.author, "${(book.progress * 100).toInt()}%").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EmptyLibrary(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BookCover("Any book, read aloud", "You", Modifier.width(150.dp).aspectRatio(0.68f))
        }
        Spacer(Modifier.height(28.dp))
        Text("Turn any document into an audiobook you can talk to.", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        Feature(Icons.Default.Headphones, "PDF, EPUB, Word, web pages and text, read in a natural voice")
        Feature(Icons.Default.Mic, "Say \"bookmark this\", \"go back 30 seconds\" or ask \"who is this?\"")
        Feature(Icons.Default.Highlight, "Highlights and notes you can export to Anki or your notes app")
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onAdd) { Text("Add your first book") }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Up late?"
}
