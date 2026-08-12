package com.minimx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- palettes ---------------------------------------------------------------

/**
 * Four roles is the whole vocabulary: page, text, quiet text, and the one colour used to
 * mark a selection. Anything needing a fifth role is drawing too much.
 */
data class Palette(
    val id: String,
    val label: String,
    val bg: Color,
    val fg: Color,
    val dim: Color,
    val accent: Color,
)

val PALETTES = listOf(
    Palette("mono-dark", "mono dark", Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF777777), Color(0xFFFFFFFF)),
    Palette("mono-light", "mono light", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF666666), Color(0xFF000000)),
    Palette("mocha", "catppuccin mocha", Color(0xFF1E1E2E), Color(0xFFCDD6F4), Color(0xFF7F849C), Color(0xFFCBA6F7)),
    Palette("latte", "catppuccin latte", Color(0xFFEFF1F5), Color(0xFF4C4F69), Color(0xFF8C8FA1), Color(0xFF8839EF)),
    Palette("one-dark", "one dark", Color(0xFF282C34), Color(0xFFABB2BF), Color(0xFF5C6370), Color(0xFF61AFEF)),
    Palette("nord", "nord", Color(0xFF2E3440), Color(0xFFD8DEE9), Color(0xFF616E88), Color(0xFF88C0D0)),
    Palette("gruvbox", "gruvbox dark", Color(0xFF282828), Color(0xFFEBDBB2), Color(0xFF928374), Color(0xFFFE8019)),
    Palette("tokyo-night", "tokyo night", Color(0xFF1A1B26), Color(0xFFC0CAF5), Color(0xFF565F89), Color(0xFF7AA2F7)),
)

fun paletteOf(id: String): Palette = PALETTES.firstOrNull { it.id == id } ?: PALETTES.first()

// --- fonts ------------------------------------------------------------------

/**
 * System families resolve instantly and work offline. Google Fonts arrive through the
 * Play Services provider — asynchronously, and not at all without network, which is why
 * every downloadable choice names a system family to fall back to.
 */
data class FontChoice(val id: String, val label: String, val google: String? = null) {
    val downloadable: Boolean get() = google != null
}

val FONTS = listOf(
    FontChoice("mono", "monospace"),
    FontChoice("sans", "sans"),
    FontChoice("serif", "serif"),
    FontChoice("jetbrains", "jetbrains mono", google = "JetBrains Mono"),
    FontChoice("plex", "ibm plex mono", google = "IBM Plex Mono"),
    FontChoice("space", "space mono", google = "Space Mono"),
    FontChoice("inter", "inter", google = "Inter"),
    FontChoice("literata", "literata", google = "Literata"),
)

fun fontOf(id: String): FontChoice = FONTS.firstOrNull { it.id == id } ?: FONTS.first()

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun familyOf(choice: FontChoice): FontFamily = when {
    // Compose draws with the platform default while the download is in flight, and keeps
    // drawing it if the provider never answers — a missing font degrades, it never blocks.
    choice.google != null -> FontFamily(Font(GoogleFont(choice.google), provider, FontWeight.Normal))
    choice.id == "sans" -> FontFamily.SansSerif
    choice.id == "serif" -> FontFamily.Serif
    else -> FontFamily.Monospace
}

// --- text sizes -------------------------------------------------------------

data class TextScale(val id: String, val label: String, val base: Int)

val SCALES = listOf(
    TextScale("s", "small", 15),
    TextScale("m", "medium", 18),
    TextScale("l", "large", 22),
    TextScale("xl", "extra large", 26),
)

fun scaleOf(id: String): TextScale = SCALES.firstOrNull { it.id == id } ?: SCALES[1]

// --- the resolved look ------------------------------------------------------

/** Everything the UI needs to draw itself, resolved once per settings change. */
data class Styles(
    val palette: Palette,
    val text: TextStyle,
    val dim: TextStyle,
    val accent: TextStyle,
    val big: TextStyle,
) {
    val bg: Color get() = palette.bg
}

fun stylesFor(paletteId: String, fontId: String, scaleId: String): Styles {
    val palette = paletteOf(paletteId)
    val family = familyOf(fontOf(fontId))
    val base = scaleOf(scaleId).base
    val text = TextStyle(fontFamily = family, color = palette.fg, fontSize = base.sp)
    return Styles(
        palette = palette,
        text = text,
        dim = text.copy(color = palette.dim),
        accent = text.copy(color = palette.accent),
        // The clock is the one thing allowed to shout.
        big = text.copy(fontSize = (base * 2.9f).sp),
    )
}

val LocalStyles = staticCompositionLocalOf { stylesFor("mono-dark", "mono", "m") }

@Composable
fun styles(): Styles = LocalStyles.current

// --- shared primitives ------------------------------------------------------

/**
 * One tappable row of text, optionally with a quiet value pushed to the right edge.
 * Every screen in minimx is a stack of these.
 */
@Composable
fun Line(
    text: String,
    dim: Boolean = false,
    suffix: String? = null,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val look = styles()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tap(onClick, onLong)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = if (dim) look.dim else look.text)
        if (suffix != null) {
            Spacer(Modifier.weight(1f))
            BasicText(suffix, style = look.dim)
        }
    }
}

/**
 * The bottom-of-screen filter field, at the thumb end. ponytail: tap to focus, never
 * autofocus — a keyboard that pops open on every swipe is worse than one tap.
 */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String = "search") {
    val look = styles()
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        BasicText("> ", style = look.text)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) BasicText(placeholder, style = look.dim)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = look.text,
                singleLine = true,
                cursorBrush = SolidColor(look.palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            BasicText("  x", style = look.dim, modifier = Modifier.tap({ onQuery("") }))
        }
    }
}

/** Clickable with no ripple — Material's touch feedback has no place here. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tap(onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
        onLongClick = onLong,
    )
}
