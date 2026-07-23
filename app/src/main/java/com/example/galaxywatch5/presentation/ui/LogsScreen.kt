package com.example.galaxywatch5.presentation.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.example.galaxywatch5.presentation.theme.AvatarTokens
import com.example.galaxywatch5.presentation.theme.AvatarWearScaffold
import com.example.galaxywatch5.presentation.logging.SessionFile
import com.example.galaxywatch5.presentation.logging.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-watch log browser: lists session .jsonl files, opens one to show its lines, and can
 * delete a file. Read-only over [SessionStore] — it owns no sensor or file logic itself.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<SessionFile?>(null) }
    val sessions = remember(refresh) { SessionStore.listSessions(context) }

    val sel = selected
    if (sel == null) {
        SessionList(
            sessions = sessions,
            onOpen = { selected = it },
            onBack = onBack,
            onClearAll = {
                SessionStore.deleteAll(context)
                refresh++
            },
        )
    } else {
        SessionDetail(
            session = sel,
            onBack = { selected = null },
            onDelete = {
                SessionStore.delete(sel.file)
                selected = null
                refresh++
            },
        )
    }
}

@Composable
private fun SessionList(
    sessions: List<SessionFile>,
    onOpen: (SessionFile) -> Unit,
    onBack: () -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(state = state, contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Sessions (${sessions.size})", style = AvatarTokens.Title,
                textAlign = TextAlign.Center) } }

            if (sessions.isEmpty()) {
                item { Text("No log files yet.\nRun a sensor, then Stop to save one.",
                    style = AvatarTokens.Label, textAlign = TextAlign.Center) }
            } else {
                items(sessions) { s ->
                    Chip(
                        label = { Text(s.name, style = AvatarTokens.ChipLabel) },
                        secondaryLabel = {
                            Text("${formatSize(s.sizeBytes)} · ${formatTime(s.lastModified)}",
                                style = AvatarTokens.Label)
                        },
                        onClick = { onOpen(s) },
                        colors = AvatarTokens.chipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    if (!confirmClear) {
                        Chip(label = { Text("Clear all (${sessions.size})", style = AvatarTokens.ChipLabel) },
                            onClick = { confirmClear = true }, colors = AvatarTokens.dangerChipColors(),
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        Chip(label = { Text("Tap again to delete all", style = AvatarTokens.ChipLabel) },
                            onClick = { onClearAll(); confirmClear = false },
                            colors = AvatarTokens.dangerChipColors(), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (confirmClear) {
                    item {
                        CompactChip(label = { Text("Cancel", style = AvatarTokens.ChipLabel) },
                            onClick = { confirmClear = false }, colors = AvatarTokens.chipColors(),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                CompactChip(label = { Text("Back", style = AvatarTokens.ChipLabel) },
                    onClick = onBack, colors = AvatarTokens.chipColors(active = false),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SessionDetail(
    session: SessionFile,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val result = remember(session) { SessionStore.readLines(session.file) }
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(state = state, contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(session.name, style = AvatarTokens.Title,
                textAlign = TextAlign.Center) } }
            item {
                Text("${result.lines.size} lines${if (result.truncated) " (first 400)" else ""}",
                    style = AvatarTokens.Label, textAlign = TextAlign.Center)
            }
            items(result.lines) { line ->
                Text(line, style = AvatarTokens.Caption.copy(color = AvatarTokens.Ink),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
            }
            item {
                CompactChip(label = { Text("Back to sessions", style = AvatarTokens.ChipLabel) },
                    onClick = onBack, colors = AvatarTokens.chipColors(active = false),
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                Chip(label = { Text("Delete this file", style = AvatarTokens.ChipLabel) },
                    onClick = onDelete, colors = AvatarTokens.dangerChipColors(),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
