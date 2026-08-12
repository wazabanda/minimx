package com.minimx

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

val WORK_OPTIONS = listOf(15, 25, 45, 60)
val BREAK_OPTIONS = listOf(5, 10, 15)

enum class Phase { WORK, BREAK }

/**
 * A focus session is two values in prefs: when it ends, and which phase it is. Nothing
 * runs between ticks — every screen derives the countdown from the wall clock, and a
 * single alarm exists only to make a noise at the end. Kill the process, reopen it, the
 * session is still there and still correct.
 */
class Focus(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("minimx", Context.MODE_PRIVATE)

    var workMinutes: Int
        get() = prefs.getInt(WORK, 25)
        set(v) = prefs.edit().putInt(WORK, v).apply()

    var breakMinutes: Int
        get() = prefs.getInt(BREAK, 5)
        set(v) = prefs.edit().putInt(BREAK, v).apply()

    private val endsAt: Long get() = prefs.getLong(ENDS_AT, 0L)

    val phase: Phase
        get() = if (prefs.getString(PHASE, Phase.WORK.name) == Phase.BREAK.name) Phase.BREAK else Phase.WORK

    /** Milliseconds left, or 0 when no session is running. */
    fun remainingMs(): Long = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)

    fun isRunning(): Boolean = remainingMs() > 0

    /** A session that reached its end and has not been acknowledged yet. */
    fun isFinished(): Boolean = endsAt > 0L && remainingMs() == 0L

    /** Work sessions block apps. Breaks are breaks. */
    fun isBlocking(): Boolean = isRunning() && phase == Phase.WORK

    fun start(phase: Phase) {
        val minutes = if (phase == Phase.WORK) workMinutes else breakMinutes
        val total = minutes * 60_000L
        val end = System.currentTimeMillis() + total
        // Total is stored, not recomputed from workMinutes: changing the length setting
        // mid-session must not warp the ring of a session already running.
        prefs.edit()
            .putLong(ENDS_AT, end)
            .putLong(TOTAL, total)
            .putString(PHASE, phase.name)
            .apply()
        scheduleAlarm(end)
    }

    /** 1f at the start, 0f at the end. */
    fun progress(): Float {
        val total = prefs.getLong(TOTAL, 0L)
        if (total <= 0L) return 0f
        return (remainingMs().toFloat() / total).coerceIn(0f, 1f)
    }

    fun stop() {
        prefs.edit().remove(ENDS_AT).apply()
        cancelAlarm()
    }

    /** Clears the finished marker without starting anything. */
    fun acknowledge() = prefs.edit().remove(ENDS_AT).apply()

    // --- allowlist ----------------------------------------------------------
    // Focus blocks everything except these packages, so an empty allowlist means a
    // session locks the whole phone down. Apps are allowed one at a time from the
    // long-press menu, which is also where you find out what is blocked.

    var allowed: Set<String>
        get() = prefs.getString(ALLOWED, "").orEmpty().split("\n").filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString(ALLOWED, v.joinToString("\n")).apply()

    fun toggleAllowed(pkg: String) {
        allowed = if (pkg in allowed) allowed - pkg else allowed + pkg
    }

    fun blocks(pkg: String): Boolean = isBlocking() && pkg !in allowed

    // --- alarm --------------------------------------------------------------

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, FocusEndReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleAlarm(at: Long) {
        val manager = ctx.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent()
        // setAlarmClock survives doze. It needs exact-alarm permission on API 31+; if the
        // system refuses, an inexact alarm still fires, just possibly late. The countdown
        // itself is never wrong either way — it is derived from the clock, not the alarm.
        runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, pending), pending)
        }.onFailure {
            manager.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun cancelAlarm() = ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent())

    private companion object {
        const val ENDS_AT = "focus_ends_at"
        const val TOTAL = "focus_total"
        const val PHASE = "focus_phase"
        const val WORK = "focus_work"
        const val BREAK = "focus_break"
        const val ALLOWED = "focus_allowed"
    }
}

/** Fires when a session's time is up. Posts one notification, does nothing else. */
class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "focus", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val finished = Focus(context)
        val text = if (finished.phase == Phase.BREAK) "break over" else "focus session done"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("minimx")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Without POST_NOTIFICATIONS this throws on API 33+; the session already ended
        // correctly, so a missing notification is not worth crashing over.
        runCatching { manager.notify(1, notification) }
    }

    private companion object {
        const val CHANNEL = "focus"
    }
}

// --- tasks ------------------------------------------------------------------

data class Task(val text: String, val done: Boolean)

/**
 * A plain text list in prefs, one task per line, prefixed `x ` or `o `. No database, no
 * ids, no sync — a to-do list that outgrows this deserves a real app, not a launcher.
 */
class Tasks(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("minimx", Context.MODE_PRIVATE)

    var all: List<Task>
        get() = prefs.getString(KEY, "").orEmpty()
            .split("\n")
            .filter { it.length > 2 }
            .map { Task(it.substring(2), it.startsWith("x ")) }
        set(v) = prefs.edit()
            .putString(KEY, v.joinToString("\n") { (if (it.done) "x " else "o ") + it.text })
            .apply()

    fun add(text: String) {
        val clean = text.trim().replace("\n", " ")
        if (clean.isNotEmpty()) all = all + Task(clean, done = false)
    }

    fun toggle(index: Int) {
        all = all.mapIndexed { i, t -> if (i == index) t.copy(done = !t.done) else t }
    }

    fun remove(index: Int) {
        all = all.filterIndexed { i, _ -> i != index }
    }

    fun clearDone() {
        all = all.filterNot { it.done }
    }

    private companion object {
        const val KEY = "tasks"
    }
}

/** "14:32" — mm:ss for anything under an hour, h:mm:ss above. */
fun formatCountdown(ms: Long): String {
    val total = (ms + 999) / 1000          // round up, so a fresh 25m session reads 25:00
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
