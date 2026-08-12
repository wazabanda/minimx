@file:OptIn(ExperimentalFoundationApi::class)

package com.minimx

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- terminal palette -------------------------------------------------------

private val BG = Color.Black
private val FG = Color.White
private val DIM = Color(0xFF777777)

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, color = FG, fontSize = 18.sp)
private val MonoDim = Mono.copy(color = DIM)
private val MonoBig = Mono.copy(fontSize = 52.sp)

// ponytail: one length for every app. Per-app pause durations if 5s stops working.
private const val PAUSE_SECONDS = 5

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM")

class MainActivity : ComponentActivity() {

    private lateinit var apps: Apps
    private val list = MutableStateFlow<List<App>>(emptyList())

    /** Bumped on every resume and HOME press. Everything downstream resets to the home page. */
    private val reset = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        apps = Apps(this)
        setContent {
            Launcher(
                apps = apps,
                installed = list.collectAsState().value,
                reset = reset.collectAsState().value,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reset.value++
        // ponytail: re-query on resume instead of a LauncherApps.Callback. ~200 apps is
        // a few ms off the main thread. Add the callback if resume ever feels slow.
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.Default) { apps.load() }
            list.value = loaded
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reset.value++
    }
}

// --- overlays ---------------------------------------------------------------

private sealed interface Overlay {
    data class Menu(val app: App) : Overlay
    data class LimitEntry(val app: App) : Overlay
    data class Pause(val app: App, val minutesLeft: Int, val limitMin: Int) : Overlay
}

@Composable
private fun Launcher(apps: Apps, installed: List<App>, reset: Int) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 2 })
    var query by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // Pin/hide/limit edits are written straight to prefs; this counter re-reads them.
    var prefsRev by remember { mutableStateOf(0) }

    // pkg -> minutes left today. Off the main thread: it is a binder call to usage stats.
    var remaining by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(reset, prefsRev) {
        remaining = withContext(Dispatchers.Default) { apps.remainingToday() }
    }

    LaunchedEffect(reset) {
        now = LocalDateTime.now()
        query = ""
        overlay = null
        prefsRev++
        pager.scrollToPage(0)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = LocalDateTime.now()
        }
    }

    fun launch(app: App) {
        // Budgeted apps get the pause screen. `remaining` was refreshed on resume, and
        // nothing can have used the app since, so no usage query is needed at tap time.
        val left = remaining[app.pkg]
        if (left != null) {
            overlay = Overlay.Pause(app, left, apps.limit(app.pkg))
        } else {
            apps.launch(app)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        when (val o = overlay) {
            null -> {
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    if (page == 0) {
                        Home(
                            apps = apps,
                            installed = installed,
                            now = now,
                            prefsRev = prefsRev,
                            remaining = remaining,
                            onLaunch = ::launch,
                            onMenu = { overlay = Overlay.Menu(it) },
                        )
                    } else {
                        Drawer(
                            apps = apps,
                            installed = installed,
                            query = query,
                            prefsRev = prefsRev,
                            remaining = remaining,
                            onQuery = { query = it },
                            onLaunch = ::launch,
                            onMenu = { overlay = Overlay.Menu(it) },
                        )
                    }
                }
                // Drawer -> home. Home -> nothing; back on a launcher must be inert.
                BackHandler(enabled = true) {
                    if (pager.currentPage != 0) {
                        query = ""
                        scope.launch { pager.animateScrollToPage(0) }
                    }
                }
            }

            is Overlay.Menu -> Menu(
                apps = apps,
                app = o.app,
                onEditLimit = { overlay = Overlay.LimitEntry(o.app) },
                onChanged = { prefsRev++ },
                onClose = { overlay = null },
            )

            is Overlay.LimitEntry -> LimitEntry(
                apps = apps,
                app = o.app,
                onDone = { prefsRev++; overlay = Overlay.Menu(o.app) },
            )

            is Overlay.Pause -> Pause(
                app = o.app,
                minutesLeft = o.minutesLeft,
                limitMin = o.limitMin,
                onOpen = { overlay = null; apps.launch(o.app) },
                onClose = { overlay = null },
            )
        }
    }
}

// --- home -------------------------------------------------------------------

@Composable
private fun Home(
    apps: Apps,
    installed: List<App>,
    now: LocalDateTime,
    prefsRev: Int,
    remaining: Map<String, Int>,
    onLaunch: (App) -> Unit,
    onMenu: (App) -> Unit,
) {
    val ctx = LocalContext.current
    val pinned = remember(installed, prefsRev) {
        val byKey = installed.associateBy { it.key }
        apps.pinned.mapNotNull { byKey[it] }
    }
    val isDefault = remember(prefsRev) { apps.isDefaultLauncher() }
    // Without usage access the budgets are set but never counted, and nothing would say so.
    val limitsBlind = remember(prefsRev) { apps.limits().isNotEmpty() && !apps.hasUsageAccess() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        BasicText(now.format(TIME), style = MonoBig)
        BasicText(now.format(DATE).lowercase(), style = MonoDim)
        Spacer(Modifier.height(48.dp))

        if (pinned.isEmpty()) {
            BasicText("swipe up for apps", style = MonoDim, modifier = Modifier.padding(vertical = 10.dp))
            BasicText("long-press one to pin it here", style = MonoDim)
        }
        pinned.forEach { app ->
            Line(
                app.label,
                suffix = remaining[app.pkg]?.let { "${it}m left" },
                onClick = { onLaunch(app) },
                onLong = { onMenu(app) },
            )
        }

        Spacer(Modifier.weight(1f))

        if (limitsBlind) {
            Line("> limits need usage access", dim = true) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        if (!isDefault) {
            Line("> set as default launcher", dim = true) {
                ctx.startActivity(apps.requestHomeRole())
            }
        }
    }
}

// --- drawer -----------------------------------------------------------------

@Composable
private fun Drawer(
    apps: Apps,
    installed: List<App>,
    query: String,
    prefsRev: Int,
    remaining: Map<String, Int>,
    onQuery: (String) -> Unit,
    onLaunch: (App) -> Unit,
    onMenu: (App) -> Unit,
) {
    val hidden = remember(prefsRev) { apps.hidden }
    val visible = remember(installed, hidden, query) {
        installed.filter { it.key !in hidden && it.label.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(visible, key = { it.key }) { app ->
                Line(
                    app.label,
                    suffix = remaining[app.pkg]?.let { "${it}m left" },
                    onClick = { onLaunch(app) },
                    onLong = { onMenu(app) },
                )
            }
        }
        Search(query, onQuery, onSubmit = { visible.firstOrNull()?.let(onLaunch) })
    }
}

@Composable
private fun Search(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        BasicText("> ", style = Mono)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) BasicText("search", style = MonoDim)
            // ponytail: tap to focus, no autofocus. A keyboard that pops on every
            // swipe-up is worse than one tap.
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = Mono,
                singleLine = true,
                cursorBrush = SolidColor(FG),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            BasicText(
                "  x",
                style = MonoDim,
                modifier = Modifier.tap({ onQuery("") }),
            )
        }
    }
}

// --- overlays ---------------------------------------------------------------

@Composable
private fun Menu(
    apps: Apps,
    app: App,
    onEditLimit: () -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    var rev by remember { mutableStateOf(0) }
    val isPinned = remember(rev) { app.key in apps.pinned }
    val isHidden = remember(rev) { app.key in apps.hidden }
    val limit = remember(rev) { apps.limit(app.pkg) }
    // One binder call on a screen you reach by long-pressing — not worth going async for.
    val usedMs = remember(rev) { if (limit > 0) apps.usedTodayMs(app.pkg) else 0L }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        BasicText(app.label, style = Mono)
        BasicText(app.pkg, style = MonoDim)
        Spacer(Modifier.height(32.dp))

        Line(if (isPinned) "> unpin" else "> pin") { apps.togglePin(app.key); rev++; onChanged() }
        Line(if (isHidden) "> unhide" else "> hide") { apps.toggleHide(app.key); rev++; onChanged() }
        Line(
            text = if (limit > 0) "> limit  ${limit}m/day" else "> limit  none",
            suffix = if (limit > 0) "${minutesLeft(limit, usedMs)}m left" else null,
            onClick = onEditLimit,
        )
        Line("> app info") { apps.appInfo(app); onClose() }
        Line("> back", dim = true, onClick = onClose)
    }
}

@Composable
private fun LimitEntry(apps: Apps, app: App, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(apps.limit(app.pkg).takeIf { it > 0 }?.toString() ?: "") }
    val focus = remember { FocusRequester() }
    val hasAccess = remember { apps.hasUsageAccess() }

    BackHandler { onDone() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp).imePadding()) {
        BasicText(app.label, style = Mono)
        BasicText("daily limit, minutes", style = MonoDim)
        Spacer(Modifier.height(32.dp))

        Row {
            BasicText("> ", style = Mono)
            BasicTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                textStyle = Mono,
                singleLine = true,
                cursorBrush = SolidColor(FG),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }

        Spacer(Modifier.height(32.dp))
        Line("> save") { apps.setLimit(app.pkg, text.toIntOrNull() ?: 0); onDone() }
        Line("> remove limit") { apps.setLimit(app.pkg, 0); onDone() }
        Line("> back", dim = true, onClick = onDone)

        if (!hasAccess) {
            Spacer(Modifier.height(32.dp))
            BasicText("limits need usage access", style = MonoDim)
            Line("> grant", dim = true) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }
}

/**
 * The breath before a budgeted app opens. Counts down, shows what is left of the day's
 * budget, and only then offers the way in — the delay is the whole point, so `> open`
 * does not exist until the timer runs out.
 */
@Composable
private fun Pause(app: App, minutesLeft: Int, limitMin: Int, onOpen: () -> Unit, onClose: () -> Unit) {
    var seconds by remember(app.key) { mutableStateOf(PAUSE_SECONDS) }
    LaunchedEffect(app.key) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        Spacer(Modifier.weight(1f))
        BasicText(app.label, style = Mono)
        BasicText(
            if (minutesLeft > 0) "${minutesLeft}m left of ${limitMin}m today"
            else "${limitMin}m/day spent — over budget",
            style = MonoDim,
        )
        Spacer(Modifier.height(48.dp))

        if (seconds > 0) {
            BasicText("$seconds", style = MonoBig)
        } else {
            // ponytail: soft gate. A hard block needs an AccessibilityService watching the
            // foreground app — real battery cost, and only helps if this is the only way in.
            Line(if (minutesLeft > 0) "> open" else "> open anyway") { onOpen() }
        }

        Line("> back", dim = true, onClick = onClose)
        Spacer(Modifier.weight(1f))
    }
}

// --- primitives -------------------------------------------------------------

@Composable
private fun Line(
    text: String,
    dim: Boolean = false,
    suffix: String? = null,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) = Row(
    modifier = Modifier
        .fillMaxWidth()
        .tap(onClick, onLong)
        .padding(horizontal = 24.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    BasicText(text, style = if (dim) MonoDim else Mono)
    if (suffix != null) {
        Spacer(Modifier.weight(1f))
        BasicText(suffix, style = MonoDim)
    }
}

/** Clickable with no ripple — Material's touch feedback has no place here. */
@Composable
private fun Modifier.tap(onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
        onLongClick = onLong,
    )
}
