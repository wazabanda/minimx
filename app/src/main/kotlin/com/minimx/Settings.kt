package com.minimx

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Header shared by every settings screen: a title, then rows. */
@Composable
private fun Screen(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val look = styles()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        BasicText(title, style = look.text)
        if (subtitle != null) BasicText(subtitle, style = look.dim)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
fun SettingsScreen(
    onAppearance: () -> Unit,
    onWidgets: () -> Unit,
    onHidden: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    Screen("settings") {
        Line("> appearance", onClick = onAppearance)
        Line("> widgets", onClick = onWidgets)
        Line("> hidden apps", onClick = onHidden)
        Line("> back", dim = true, onClick = onClose)
    }
}

// --- appearance -------------------------------------------------------------

private enum class Picking { NONE, THEME, FONT, SIZE }

@Composable
fun AppearanceScreen(apps: Apps, onChanged: () -> Unit, onClose: () -> Unit) {
    var picking by remember { mutableStateOf(Picking.NONE) }
    var rev by remember { mutableStateOf(0) }

    // Back closes the open picker first, then the screen — one level at a time.
    BackHandler { if (picking == Picking.NONE) onClose() else picking = Picking.NONE }

    val palette = remember(rev) { paletteOf(apps.paletteId) }
    val font = remember(rev) { fontOf(apps.fontId) }
    val scale = remember(rev) { scaleOf(apps.scaleId) }

    when (picking) {
        Picking.NONE -> Screen("appearance") {
            Line("> theme", suffix = palette.label) { picking = Picking.THEME }
            Line("> font", suffix = font.label) { picking = Picking.FONT }
            Line("> text size", suffix = scale.label) { picking = Picking.SIZE }
            Line("> back", dim = true, onClick = onClose)
        }

        Picking.THEME -> Screen("theme") {
            PALETTES.forEach { option ->
                Line(
                    text = if (option.id == palette.id) "> ${option.label}" else "  ${option.label}",
                    dim = option.id != palette.id,
                ) { apps.paletteId = option.id; rev++; onChanged() }
            }
            Line("> back", dim = true) { picking = Picking.NONE }
        }

        Picking.FONT -> Screen("font", "downloadable fonts need network the first time") {
            FONTS.forEach { option ->
                Line(
                    text = if (option.id == font.id) "> ${option.label}" else "  ${option.label}",
                    dim = option.id != font.id,
                    suffix = if (option.downloadable) "google" else null,
                ) { apps.fontId = option.id; rev++; onChanged() }
            }
            Line("> back", dim = true) { picking = Picking.NONE }
        }

        Picking.SIZE -> Screen("text size") {
            SCALES.forEach { option ->
                Line(
                    text = if (option.id == scale.id) "> ${option.label}" else "  ${option.label}",
                    dim = option.id != scale.id,
                ) { apps.scaleId = option.id; rev++; onChanged() }
            }
            Line("> back", dim = true) { picking = Picking.NONE }
        }
    }
}

// --- widgets ----------------------------------------------------------------

@Composable
fun WidgetScreen(apps: Apps, onChanged: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var rev by remember { mutableStateOf(0) }
    val enabled = remember(rev) { apps.widgetIds }

    // A widget that needs a permission asks for it at the moment it is switched on,
    // never while rendering. Denied -> it stays off rather than showing a dead row.
    var pending by remember { mutableStateOf<Widget?>(null) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pending?.let { if (granted) { apps.toggleWidget(it.id); rev++; onChanged() } }
        pending = null
    }

    BackHandler { onClose() }

    Screen("widgets", "shown under the clock, in this order") {
        WIDGETS.forEach { widget ->
            val on = widget.id in enabled
            Line(
                text = if (on) "> ${widget.label}" else "  ${widget.label}",
                dim = !on,
                suffix = if (on) "on" else "off",
            ) {
                val needs = widget.permission
                val hasIt = needs == null ||
                    ContextCompat.checkSelfPermission(ctx, needs) == PackageManager.PERMISSION_GRANTED
                when {
                    on || hasIt -> { apps.toggleWidget(widget.id); rev++; onChanged() }
                    else -> { pending = widget; request.launch(needs!!) }
                }
            }
        }
        Line("> back", dim = true, onClick = onClose)
    }
}

// --- focus allowlist --------------------------------------------------------

/**
 * Pick what stays reachable during a work session. Allowed apps float to the top so the
 * current selection is visible without hunting for it; everything else is alphabetical
 * behind the filter.
 */
@Composable
fun AllowListScreen(
    focus: Focus,
    installed: List<App>,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val look = styles()
    var query by remember { mutableStateOf("") }
    var rev by remember { mutableStateOf(0) }
    val allowed = remember(rev) { focus.allowed }
    val visible = remember(installed, allowed, query) {
        installed
            // One row per app, not per launcher activity — the allowlist is package-level.
            .distinctBy { it.pkg }
            .filter { it.label.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<App> { it.pkg in allowed }.thenBy { it.label.lowercase() })
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().imePadding().padding(vertical = 32.dp)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            BasicText("allowed in focus", style = look.text)
            BasicText(
                if (allowed.isEmpty()) "nothing allowed — a session blocks everything"
                else "${allowed.size} apps stay open during focus",
                style = look.dim,
            )
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.pkg }) { app ->
                val on = app.pkg in allowed
                Line(
                    text = if (on) "> ${app.label}" else "  ${app.label}",
                    dim = !on,
                    suffix = if (on) "allowed" else null,
                ) { focus.toggleAllowed(app.pkg); rev++; onChanged() }
            }
        }

        Line("> back", dim = true, onClick = onClose)
        SearchField(query, { query = it }, placeholder = "filter apps")
    }
}

// --- hidden apps ------------------------------------------------------------

@Composable
fun HiddenAppsScreen(apps: Apps, installed: List<App>, onChanged: () -> Unit, onClose: () -> Unit) {
    val look = styles()
    var rev by remember { mutableStateOf(0) }
    val hidden = remember(rev, installed) {
        val keys = apps.hidden
        installed.filter { it.key in keys }
    }

    BackHandler { onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        BasicText("hidden apps", style = look.text)
        BasicText("tap to unhide", style = look.dim)
        Spacer(Modifier.height(24.dp))

        if (hidden.isEmpty()) {
            BasicText("nothing hidden", style = look.dim, modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(hidden, key = { it.key }) { app ->
                Line(app.label) { apps.toggleHide(app.key); rev++; onChanged() }
            }
        }
        Line("> back", dim = true, onClick = onClose)
    }
}
