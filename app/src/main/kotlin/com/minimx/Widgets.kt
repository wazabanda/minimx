package com.minimx

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A widget is one line of live text on the home screen. That is the entire contract.
 *
 * Rules that keep the layer honest:
 *  - `line()` returns null when there is nothing worth saying, and the row disappears
 *    entirely. A widget that renders "no events" is noise wearing a widget costume.
 *  - `line()` is @Composable, so a widget owns its own refresh (a ticking LaunchedEffect,
 *    a broadcast receiver) instead of the host polling everything on a shared timer.
 *  - Anything blocking goes on Dispatchers.IO inside the widget, never on the caller.
 *  - `permission` is null for widgets that need nothing. The settings screen requests it
 *    when the widget is switched on, so a widget never asks mid-render.
 *
 * To add one: implement this, add it to WIDGETS. There is no registration, no manifest
 * entry, no config screen to write.
 */
interface Widget {
    val id: String
    val label: String
    val permission: String? get() = null

    @Composable
    fun line(): String?

    /**
     * How the row is drawn. The default renders `line()` as quiet text, which is all any
     * widget needed until the screen-time mascot wanted a sprite next to its number.
     * Override only when text genuinely cannot say it.
     */
    @Composable
    fun render() {
        line()?.let { BasicText(it, style = styles().dim) }
    }
}

val WIDGETS: List<Widget> = listOf(BatteryWidget, AlarmWidget, EventWidget, ScreenTimeWidget)

fun widgetOf(id: String): Widget? = WIDGETS.firstOrNull { it.id == id }

// --- battery ----------------------------------------------------------------

object BatteryWidget : Widget {
    override val id = "battery"
    override val label = "battery"

    @Composable
    override fun line(): String {
        val ctx = LocalContext.current
        var text by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val pct = if (level < 0) 0 else level * 100 / scale
                text = if (plugged) "$pct% charging" else "$pct%"
                delay(60_000)
            }
        }
        return text
    }
}

// --- next alarm -------------------------------------------------------------

object AlarmWidget : Widget {
    override val id = "alarm"
    override val label = "next alarm"

    @Composable
    override fun line(): String? {
        val ctx = LocalContext.current
        var text by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                // No permission needed — the system exposes the next alarm to whoever asks.
                val next = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
                text = next?.let {
                    "alarm " + Instant.ofEpochMilli(it.triggerTime)
                        .atZone(ZoneId.systemDefault()).format(ALARM_FORMAT)
                }
                delay(60_000)
            }
        }
        return text
    }

    private val ALARM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
}

// --- next calendar event ----------------------------------------------------

object EventWidget : Widget {
    override val id = "event"
    override val label = "next event"
    override val permission = Manifest.permission.READ_CALENDAR

    @Composable
    override fun line(): String? {
        val ctx = LocalContext.current
        var text by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                text = withContext(Dispatchers.IO) { nextEvent(ctx) }
                delay(5 * 60_000)
            }
        }
        return text
    }

    /** Title and start of the next event in the coming 24h, or null. */
    private fun nextEvent(ctx: Context): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val now = System.currentTimeMillis()
        // CalendarContract.Instances expands recurring events; the plain Events table does not.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath((now + 24 * 60 * 60_000L).toString())
            .build()
        val cursor: Cursor? = ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )
        return cursor?.use {
            if (!it.moveToFirst()) return@use null
            val title = it.getString(0)?.takeIf(String::isNotBlank) ?: "(untitled)"
            val begin = it.getLong(1)
            val at = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).format(EVENT_FORMAT)
            "$title $at"
        }
    }

    private val EVENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}

// --- screen time today ------------------------------------------------------

object ScreenTimeWidget : Widget {
    override val id = "screentime"
    override val label = "screen time today"

    @Composable
    private fun minutesToday(): Long? {
        val ctx = LocalContext.current
        val apps = remember { Apps(ctx) }
        var minutes by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                minutes = withContext(Dispatchers.IO) { apps.screenTimeTodayMs()?.div(60_000) }
                delay(5 * 60_000)
            }
        }
        return minutes
    }

    @Composable
    override fun line(): String? = minutesToday()?.let { minutes ->
        if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"
    }

    @Composable
    override fun render() {
        val look = styles()
        val minutes = minutesToday() ?: return
        val stage = mascotStage(minutes)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(MASCOT_FRAMES[stage]),
                contentDescription = null,
                // Single-colour silhouette tinted by the theme. It turns to the accent
                // colour at the last stage, which is the only moment worth alarm.
                colorFilter = ColorFilter.tint(
                    if (stage == MASCOT_FRAMES.lastIndex) look.palette.accent else look.palette.dim,
                ),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(Space.tight))
            BasicText(line().orEmpty(), style = look.dim)
        }
    }
}

private val MASCOT_FRAMES = listOf(
    R.drawable.mascot_0,
    R.drawable.mascot_1,
    R.drawable.mascot_2,
    R.drawable.mascot_3,
    R.drawable.mascot_4,
    R.drawable.mascot_5,
)

/**
 * Which frame the plant is on, by minutes of screen time. It is a guilt gauge, not a
 * metric: a number tells you nothing at a glance, a plant dying tells you immediately.
 * Change these numbers to move the goalposts; the last stage is terminal.
 */
private val MASCOT_STAGES = listOf(60L, 120L, 180L, 240L, 300L)

fun mascotStage(minutes: Long): Int =
    MASCOT_STAGES.indexOfFirst { minutes < it }.takeIf { it >= 0 } ?: MASCOT_STAGES.size
