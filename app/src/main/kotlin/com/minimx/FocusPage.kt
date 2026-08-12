@file:OptIn(ExperimentalFoundationApi::class)

package com.minimx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The page below home: what you are doing, and how long you have to do it.
 * Timer on top, tasks underneath, one text field at the thumb end.
 */
@Composable
fun FocusPage(
    focus: Focus,
    tasks: Tasks,
    remainingMs: Long,
    prefsRev: Int,
    onChanged: () -> Unit,
    onAllowList: () -> Unit,
) {
    val look = styles()
    var draft by remember { mutableStateOf("") }
    var rev by remember { mutableStateOf(0) }
    val list = remember(rev, prefsRev) { tasks.all }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = Space.edge, vertical = Space.section),
    ) {
        Timer(focus, remainingMs, prefsRev, onChanged, onAllowList)

        Spacer(Modifier.height(Space.section))
        BasicText("tasks", style = look.dim)

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(list, key = { i, t -> "$i ${t.text}" }) { index, task ->
                Line(
                    text = if (task.done) "x ${task.text}" else "o ${task.text}",
                    dim = task.done,
                    onLong = { tasks.remove(index); rev++; onChanged() },
                ) { tasks.toggle(index); rev++; onChanged() }
            }
        }

        if (list.any { it.done }) {
            Line("> clear done", dim = true) { tasks.clearDone(); rev++; onChanged() }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            BasicText("+ ", style = look.text)
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) BasicText("add task", style = look.dim)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = look.text,
                    singleLine = true,
                    cursorBrush = SolidColor(look.palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        tasks.add(draft); draft = ""; rev++; onChanged()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Timer(
    focus: Focus,
    remainingMs: Long,
    prefsRev: Int,
    onChanged: () -> Unit,
    onAllowList: () -> Unit,
) {
    val look = styles()
    val ctx = LocalContext.current
    var rev by remember { mutableStateOf(0) }
    var quitting by remember { mutableStateOf(false) }

    // Asked once, when you first start a session. Denied just means no end-of-session
    // sound; the countdown and the blocking do not depend on it.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun start(phase: Phase) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        focus.start(phase)
        rev++
        onChanged()
    }

    val work = remember(rev, prefsRev) { focus.workMinutes }
    val rest = remember(rev, prefsRev) { focus.breakMinutes }
    val allowed = remember(rev, prefsRev) { focus.allowed.size }

    when {
        remainingMs > 0 -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(focus.progress())
                Spacer(Modifier.width(20.dp))
                Column {
                    BasicText(formatCountdown(remainingMs), style = look.big)
                    BasicText(
                        if (focus.phase == Phase.WORK) "focus — everything but $allowed apps is blocked"
                        else "break",
                        style = look.dim,
                    )
                }
            }
            Spacer(Modifier.height(Space.tight))
            GiveUp(
                quitting = quitting,
                onAsk = { quitting = true },
                onCancel = { quitting = false },
                onQuit = { focus.stop(); quitting = false; rev++; onChanged() },
            )
            // Reachable mid-session on purpose: realising you need one more tool should
            // not cost you the whole block.
            Line("> allowed in focus", suffix = "$allowed apps", dim = true, onClick = onAllowList)
        }

        focus.isFinished() -> {
            BasicText("done", style = look.big)
            BasicText(
                if (focus.phase == Phase.WORK) "take ${rest}m" else "break over",
                style = look.dim,
            )
            Spacer(Modifier.height(Space.tight))
            if (focus.phase == Phase.WORK) {
                Line("> start break") { start(Phase.BREAK) }
            }
            Line("> start focus  ${work}m") { start(Phase.WORK) }
            Line("> dismiss", dim = true) { focus.acknowledge(); rev++; onChanged() }
        }

        else -> {
            BasicText("${work}:00", style = look.big)
            BasicText("ready", style = look.dim)
            Spacer(Modifier.height(Space.tight))
            Line("> start focus") { start(Phase.WORK) }
            // Tap to cycle. A picker screen for two numbers would be more UI than setting.
            Line("> work", suffix = "${work}m", dim = true) {
                focus.workMinutes = WORK_OPTIONS[(WORK_OPTIONS.indexOf(work) + 1) % WORK_OPTIONS.size]
                rev++; onChanged()
            }
            Line("> break", suffix = "${rest}m", dim = true) {
                focus.breakMinutes = BREAK_OPTIONS[(BREAK_OPTIONS.indexOf(rest) + 1) % BREAK_OPTIONS.size]
                rev++; onChanged()
            }
            Line("> allowed in focus", suffix = "$allowed apps", dim = true, onClick = onAllowList)
        }
    }
}

/** The session, drained clockwise. Empty ring means time is up. */
@Composable
private fun Ring(progress: Float, size: Dp = 56.dp, stroke: Dp = 3.dp) {
    val look = styles()
    Canvas(Modifier.size(size)) {
        val width = stroke.toPx()
        val inset = width / 2
        val arc = Size(this.size.width - width, this.size.height - width)
        drawArc(
            color = look.palette.dim,
            alpha = 0.3f,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arc,
            style = Stroke(width = width),
        )
        drawArc(
            color = look.palette.accent,
            // Twelve o'clock, clockwise — the direction every clock face has trained you for.
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arc,
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
    }
}

/**
 * Quitting early is possible but never one tap — the countdown is the point. Anyone
 * determined enough can uninstall the launcher, so pretending otherwise would be theatre.
 */
@Composable
private fun GiveUp(quitting: Boolean, onAsk: () -> Unit, onCancel: () -> Unit, onQuit: () -> Unit) {
    val look = styles()
    var seconds by remember(quitting) { mutableStateOf(if (quitting) GIVE_UP_SECONDS else 0) }

    if (!quitting) {
        Line("> give up", dim = true, onClick = onAsk)
        return
    }

    androidx.compose.runtime.LaunchedEffect(quitting) {
        while (seconds > 0) {
            kotlinx.coroutines.delay(1000)
            seconds--
        }
    }

    if (seconds > 0) {
        BasicText("hold on… $seconds", style = look.dim, modifier = Modifier.padding(vertical = 12.dp))
    } else {
        Line("> yes, end session", onClick = onQuit)
    }
    Line("> keep going", dim = true, onClick = onCancel)
}

private const val GIVE_UP_SECONDS = 5
