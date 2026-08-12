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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    override fun line(): String? {
        val ctx = LocalContext.current
        val apps = remember { Apps(ctx) }
        var text by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                text = withContext(Dispatchers.IO) {
                    apps.screenTimeTodayMs()?.let { ms ->
                        val minutes = ms / 60_000
                        if (minutes >= 60) "screen %dh %02dm".format(minutes / 60, minutes % 60)
                        else "screen ${minutes}m"
                    }
                }
                delay(5 * 60_000)
            }
        }
        return text
    }
}
