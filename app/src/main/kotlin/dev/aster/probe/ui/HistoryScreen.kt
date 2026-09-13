package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.LearnedSkill
import dev.aster.probe.Entry
import dev.aster.probe.Note
import dev.aster.probe.Session
import dev.aster.probe.compact
import dev.aster.probe.duration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val day = SimpleDateFormat("d MMM HH:mm", Locale.US)
private val SectionGap = 24.dp
private val LabelGap = 8.dp

/** Everything the agent has done on this phone: its state, the numbers, the sessions, what it learned. */
@Composable
fun HistoryScreen(
    sessions: List<Session>,
    matches: Map<String, Int>?,
    skills: List<LearnedSkill>,
    notes: List<Note>,
    query: String,
    loading: Boolean,
    reading: SkillFile?,
    viewing: Session?,
    viewingLines: List<Entry>?,
    onCloseViewing: () -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (Session) -> Unit,
    onOpenSkill: (LearnedSkill) -> Unit,
    onOpenNote: (Note) -> Unit,
    onCloseReading: () -> Unit,
    onBack: () -> Unit,
) {
    val shown = if (matches == null) sessions else sessions.filter { matches.containsKey(it.id) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = Gutter),
    ) {
        BackRow(title = "History", onBack = onBack)

        Spacer(Modifier.height(SectionGap))
        SectionLabel("All time")
        Spacer(Modifier.height(LabelGap))
        GroupCard {
            Row(Modifier.fillMaxWidth()) {
                StatTile("Sessions", sessions.size.toString(), Modifier.weight(1f))
                StatTile("Turns", sessions.sumOf { it.turns }.toString(), Modifier.weight(1f))
                StatTile("Tool calls", sessions.sumOf { it.calls }.toString(), Modifier.weight(1f))
            }
            RowDivider()
            Row(Modifier.fillMaxWidth()) {
                StatTile("Tokens", compact(sessions.sumOf { it.promptTokens + it.completionTokens }), Modifier.weight(1f))
                StatTile("Total time", duration(sessions.sumOf { it.lastAt - it.createdAt }), Modifier.weight(1f))
                StatTile("Skills", skills.count { it.learned }.toString(), Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(SectionGap))
        LabelRow("Sessions", if (loading) "Loading…" else if (matches == null) "${sessions.size}" else "${shown.size} of ${sessions.size}")
        Spacer(Modifier.height(LabelGap))
        SearchField(query = query, hint = "Search sessions", onQuery = onQuery)
        Spacer(Modifier.height(LabelGap))
        if (shown.isEmpty()) {
            EmptyCard(
                when {
                    loading -> "Loading sessions…"
                    query.isBlank() -> "Talk to the agent. Its sessions show here."
                    else -> "No sessions match “$query”."
                },
            )
        } else {
            GroupCard {
                shown.forEachIndexed { i, s ->
                    val hits = matches?.get(s.id)
                    ListItemRow(
                        title = s.label,
                        line2 = "${day.format(Date(s.createdAt))} · ${duration(s.lastAt - s.createdAt)}" +
                            (s.model?.let { " · ${it.substringAfterLast('/')}" } ?: ""),
                        line3 = "${s.turns} turns · ${s.calls} calls · ${compact(s.promptTokens + s.completionTokens)} tokens · ${s.errors} errors" +
                            (hits?.let { " · $it matches" } ?: ""),
                        accent = hits != null,
                        onClick = { onOpen(s) },
                    )
                    if (i < shown.lastIndex) RowDivider()
                }
            }
        }

        Spacer(Modifier.height(SectionGap))
        LabelRow("Skills", skills.size.toString())
        Spacer(Modifier.height(LabelGap))
        if (skills.isEmpty()) {
            EmptyCard("The agent writes a skill here each time it completes a task.")
        } else {
            GroupCard {
                skills.forEachIndexed { i, sk ->
                    ListItemRow(
                        title = sk.name,
                        line2 = if (sk.learned) sk.description else "Built-in. ${sk.description}",
                        line3 = skillMeta(sk),
                        onClick = { onOpenSkill(sk) },
                    )
                    if (i < skills.lastIndex) RowDivider()
                }
            }
        }

        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(SectionGap))
            LabelRow("Memory", notes.size.toString())
            Spacer(Modifier.height(LabelGap))
            GroupCard {
                notes.forEachIndexed { i, note ->
                    ListItemRow(
                        title = note.name,
                        line2 = note.description,
                        line3 = "Remembered",
                        onClick = { onOpenNote(note) },
                    )
                    if (i < notes.lastIndex) RowDivider()
                }
            }
        }
        Spacer(Modifier.height(SectionGap))
    }
    reading?.let { SkillSheet(it, onCloseReading) }
    viewing?.let { SessionSheet(it, viewingLines, onCloseViewing) }
}

fun skillMeta(sk: LearnedSkill): String {
    val runs = "${sk.runs} ${if (sk.runs == 1) "run" else "runs"}"
    return when {
        !sk.learned -> "Built in"
        sk.bestCalls != null -> "$runs · best ${sk.bestCalls} calls"
        else -> runs
    }
}
