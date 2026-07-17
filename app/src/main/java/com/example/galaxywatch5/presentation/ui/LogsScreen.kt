package com.example.galaxywatch5.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            "Sessions (${sessions.size})",
            style = TextStyle(
                color = Color(0xFF0FE0B0), fontSize = 14.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(10.dp))

        if (sessions.isEmpty()) {
            BasicText(
                "No log files yet.\nRun a sensor, then Stop/Back to save one.",
                style = TextStyle(color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center),
            )
        } else {
            sessions.forEach { s ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1E1E))
                        .clickable { onOpen(s) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Column {
                        BasicText(
                            s.name,
                            style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            "${formatSize(s.sizeBytes)} · ${formatTime(s.lastModified)}",
                            style = TextStyle(color = Color(0xFF888888), fontSize = 9.sp),
                        )
                    }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            if (!confirmClear) {
                PillButton("Clear all (${sessions.size})", Color(0xFF3A1212)) { confirmClear = true }
            } else {
                PillButton("Tap again to delete all", Color(0xFF7A1212)) {
                    onClearAll()
                    confirmClear = false
                }
                Spacer(Modifier.height(4.dp))
                PillButton("Cancel", Color(0xFF2A2440)) { confirmClear = false }
            }
        }

        Spacer(Modifier.height(12.dp))
        PillButton("‹ Back", Color(0xFF2A2440), onBack)
    }
}

@Composable
private fun SessionDetail(
    session: SessionFile,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val result = remember(session) { SessionStore.readLines(session.file) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            session.name,
            style = TextStyle(
                color = Color(0xFF0FE0B0), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            "${result.lines.size} lines${if (result.truncated) " (first 400)" else ""}",
            style = TextStyle(color = Color(0xFF888888), fontSize = 9.sp),
        )
        Spacer(Modifier.height(8.dp))

        result.lines.forEach { line ->
            BasicText(
                line,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                style = TextStyle(color = Color(0xFF33F26B), fontSize = 8.sp),
            )
        }

        Spacer(Modifier.height(12.dp))
        PillButton("‹ Sessions", Color(0xFF2A2440), onBack)
        Spacer(Modifier.height(6.dp))
        PillButton("Delete this file", Color(0xFF3A1212), onDelete)
    }
}

@Composable
private fun PillButton(label: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 12.sp))
    }
}

private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
