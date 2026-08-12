package com.minimx

import android.app.AppOpsManager
import android.app.role.RoleManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.time.LocalDate
import java.time.ZoneId

data class App(
    val label: String,
    val pkg: String,
    val cls: String,
    val user: UserHandle,
) {
    val key: String get() = "$pkg/$cls"
    val component: ComponentName get() = ComponentName(pkg, cls)
}

/** Everything that talks to the platform: the app list, prefs, and usage stats. */
class Apps(private val ctx: Context) {

    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val userManager = ctx.getSystemService(UserManager::class.java)
    private val usage = ctx.getSystemService(UsageStatsManager::class.java)
    private val roles = ctx.getSystemService(RoleManager::class.java)
    private val prefs = ctx.getSharedPreferences("minimx", Context.MODE_PRIVATE)

    // --- app list -----------------------------------------------------------

    /** Blocking — label lookup hits the package manager. Call off the main thread. */
    fun load(): List<App> = userManager.userProfiles.flatMap { user ->
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != ctx.packageName }
            .map {
                App(it.label.toString(), it.componentName.packageName, it.componentName.className, user)
            }
    }.sortedBy { it.label.lowercase() }

    fun launch(app: App) = launcherApps.startMainActivity(app.component, app.user, null, null)

    fun appInfo(app: App) = launcherApps.startAppDetailsActivity(app.component, app.user, null, null)

    // --- prefs --------------------------------------------------------------
    // Lists are newline-joined strings, not StringSet: a StringSet loses pin order.

    var pinned: List<String>
        get() = prefs.getString(PINNED, "").orEmpty().split("\n").filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString(PINNED, v.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = prefs.getString(HIDDEN, "").orEmpty().split("\n").filter { it.isNotEmpty() }.toSet()
        set(v) = prefs.edit().putString(HIDDEN, v.joinToString("\n")).apply()

    fun togglePin(key: String) {
        pinned = if (key in pinned) pinned - key else pinned + key
    }

    fun toggleHide(key: String) {
        hidden = if (key in hidden) hidden - key else hidden + key
    }

    /** Daily budget in minutes, 0 = no limit. */
    fun limit(pkg: String): Int = prefs.getInt("$LIMIT$pkg", 0)

    fun setLimit(pkg: String, minutes: Int) =
        prefs.edit().apply { if (minutes > 0) putInt("$LIMIT$pkg", minutes) else remove("$LIMIT$pkg") }.apply()

    /** Every budget set, keyed by package. */
    fun limits(): Map<String, Int> = prefs.all
        .filterKeys { it.startsWith(LIMIT) }
        .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(LIMIT) to it } }
        .toMap()

    // --- usage stats --------------------------------------------------------

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Foreground time today, in ms. Uses queryAndAggregateUsageStats rather than
     * queryUsageStats(INTERVAL_DAILY): the daily buckets do not line up with midnight.
     */
    fun usedTodayMs(pkg: String): Long =
        usage.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())[pkg]
            ?.totalTimeInForeground ?: 0L

    /**
     * Minutes left today for every app that has a budget, floored at 0.
     * One usage query for all of them — the aggregate call already returns every package,
     * so per-app queries would be the same IPC N times.
     *
     * Empty when nothing is budgeted, or when usage access is missing: with no permission
     * the stats come back empty, and reporting a full budget would be a lie.
     */
    fun remainingToday(): Map<String, Int> {
        val limits = limits()
        if (limits.isEmpty() || !hasUsageAccess()) return emptyMap()
        val stats = usage.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
        return limits.mapValues { (pkg, budget) ->
            minutesLeft(budget, stats[pkg]?.totalTimeInForeground ?: 0L)
        }
    }

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // --- default launcher ---------------------------------------------------

    fun isDefaultLauncher(): Boolean = roles.isRoleHeld(RoleManager.ROLE_HOME)

    fun requestHomeRole() = roles.createRequestRoleIntent(RoleManager.ROLE_HOME)

    private companion object {
        const val PINNED = "pinned"
        const val HIDDEN = "hidden"
        const val LIMIT = "limit."
    }
}

/**
 * Whole minutes left in a daily budget, never negative. Partial minutes of use count
 * against you — 30m budget with 29m30s used reads as 1m left, not 0m.
 */
fun minutesLeft(budgetMinutes: Int, usedMs: Long): Int =
    (budgetMinutes - usedMs / 60_000L).coerceAtLeast(0L).toInt()
