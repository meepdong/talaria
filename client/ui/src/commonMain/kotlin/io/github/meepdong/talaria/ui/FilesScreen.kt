package io.github.meepdong.talaria.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Files: the folders next to the agent. Open a file here, or ask the agent about it. */
@Composable
fun FilesScreen(files: FilesView, actions: TalariaActions) {
    var query by remember { mutableStateOf(files.query.orEmpty()) }
    LaunchedEffect(files.query) { query = files.query.orEmpty() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp).testTag("files"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (files.roots.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                files.roots.forEach { r ->
                    FilterChip(
                        selected = r.selected,
                        onClick = { actions.openRoot(r.id) },
                        label = { Text(r.name) },
                        modifier = Modifier.testTag("root-${r.id}"),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.searchFiles(query) }),
            trailingIcon = {
                TextButton(onClick = { actions.searchFiles(query) }, modifier = Modifier.testTag("files-search")) { Text("Search") }
            },
            modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp).testTag("files-query"),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = 1120.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(files.location, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("files-location"))
                if (files.canGoUp) {
                    TextButton(onClick = { actions.filesUp() }, modifier = Modifier.testTag("files-up")) {
                        Text(if (files.query != null) "Clear search" else "Up")
                    }
                }
            }
            files.message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).testTag("files-message"))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(files.entries, key = { it.path }) { f ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FileRow(f, opening = files.opening == f.path, actions, files.openingProgress)
                }
                if (files.truncated) {
                    item {
                        Text("Showing the first 500. Search to narrow it down.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: FileItem, opening: Boolean, actions: TalariaActions, progress: Float? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { actions.openFile(f.path) }
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("file-${f.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (f.folder) Icon(TalariaIcons.Files, contentDescription = "Folder", tint = Brand.Blue)
            else Text(f.kind, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand.Blue)
        }
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!f.folder) {
            if (opening) {
                progress?.takeIf { it > 0f }?.let {
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                }
                CircularProgressIndicator(Modifier.size(20.dp).testTag("opening"), strokeWidth = 2.dp)
            }
            else TextButton(onClick = { actions.openFile(f.path) }, modifier = Modifier.testTag("open-${f.path}")) { Text("Open") }
            TextButton(onClick = { actions.askAboutFile(f.path) }, modifier = Modifier.testTag("ask-${f.path}")) { Text("Ask Hermes") }
        }
    }
}
