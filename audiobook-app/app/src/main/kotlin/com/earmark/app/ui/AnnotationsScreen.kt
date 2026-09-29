package com.earmark.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.earmark.app.playback.PlayerHub
import com.earmark.core.annotations.AnnotationKind
import com.earmark.core.annotations.BookmarkScope
import com.earmark.core.annotations.Exporters
import com.earmark.core.model.Book
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsScreen(hub: PlayerHub, onBack: () -> Unit, onJump: () -> Unit) {
    val session by hub.session.collectAsStateWithLifecycle()
    val version by hub.annotationVersion.collectAsStateWithLifecycle()
    val s = session ?: return
    val book = s.book
    val items = remember(version, book) { s.annotations.forBook(book.id) }
    val context = LocalContext.current
    var exportOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks, highlights & notes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { exportOpen = true }, enabled = items.isNotEmpty()) { Icon(Icons.Default.Share, "Export") }
                        DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                            DropdownMenuItem(text = { Text("Markdown (notes apps)") }, onClick = {
                                exportOpen = false
                                share(context, book, "md", "text/markdown", Exporters.markdown(book, items))
                            })
                            DropdownMenuItem(text = { Text("Anki flashcards (.txt)") }, onClick = {
                                exportOpen = false
                                share(context, book, "txt", "text/plain", Exporters.ankiTsv(book, items))
                            })
                            DropdownMenuItem(text = { Text("CSV (Readwise, spreadsheets)") }, onClick = {
                                exportOpen = false
                                share(context, book, "csv", "text/csv", Exporters.csv(book, items))
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nothing saved yet. While listening, say \"bookmark this\" or \"highlight that\", double-tap your earbuds, or long-press a sentence.")
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp)) {
            items(items, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { hub.seekTo(a.start.sentenceIndex); onJump() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    when (a.kind) {
                        AnnotationKind.HIGHLIGHT -> Box(Modifier.size(20.dp).background(highlightColor(a.color), RoundedCornerShape(4.dp)))
                        AnnotationKind.BOOKMARK -> Icon(Icons.Default.Bookmark, null, Modifier.size(20.dp))
                        AnnotationKind.NOTE -> Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val where = (if (book.pagesAreVirtual) "≈ page " else "Page ") + a.start.page +
                            " · " + (book.chapters.getOrNull(a.start.chapter)?.title ?: "") +
                            (if (a.scope != BookmarkScope.SENTENCE) " · ${a.scope.name.lowercase()} bookmark" else "") +
                            (if (a.orphaned) " · text not found in this edition" else "")
                        Text(where, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        a.label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        val quote = if (a.kind == AnnotationKind.HIGHLIGHT && !a.orphaned) book.textOf(a.range) else a.start.quote
                        Text(quote, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        a.note?.takeIf { it.isNotBlank() }?.let { Text("✎ $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    IconButton(onClick = { hub.removeAnnotation(a.id) }) { Icon(Icons.Default.Delete, "Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun share(context: Context, book: Book, ext: String, mime: String, content: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = book.title.replace(Regex("""[^\p{L}\p{N} _-]+"""), "").trim().take(60).ifEmpty { "book" }
    val file = File(dir, "$safe - Earmark.$ext")
    file.writeText(content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "${book.title} — highlights")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export highlights").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
