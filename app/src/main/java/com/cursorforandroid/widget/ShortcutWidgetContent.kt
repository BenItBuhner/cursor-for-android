package com.cursorforandroid.widget

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.cursorforandroid.R
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.domain.ShortcutWidgetSettings
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.CursorColors
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorOledColors
import com.cursorforandroid.ui.theme.ThemeMode

/** The composer's placeholder, as the New Chat pane words it; the compose bar wears the same line. */
const val COMPOSER_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"

/**
 * The shortcut widget, in the shape its cells allow ([ShortcutVariant]): the round button centred in its cell, or
 * the compose bar — one line of the app's composer, "+" disc, placeholder and send disc on the composer's surface
 * with its 24dp corners — or, given the height, the composer box itself. One tap, whatever the shape, opens the
 * configured target: the quick composer over the launcher, the sidebar's search, a chat, a Project.
 */
@Composable
fun ShortcutWidgetContent(settings: ShortcutWidgetSettings, variant: ShortcutVariant, appMode: ThemeMode, appOledBlack: Boolean) {
    val context = LocalContext.current
    val target = settings.target
    val look = remember(settings.style, settings.appearance, appMode, appOledBlack) { ShortcutLook.of(settings.style, settings.appearance, appMode, appOledBlack) }
    // A bar is a line of text, and text needs a surface under it on a wallpaper: the icon-only look's bar is glass.
    val barLook = remember(look, settings.appearance, appMode, appOledBlack) {
        if (look.fill == null) ShortcutLook.of(ShortcutStyle.Glass, settings.appearance, appMode, appOledBlack) else look
    }
    val open = actionStartActivity(ShortcutIntents.forTarget(context, target))
    Box(GlanceModifier.fillMaxSize().appWidgetBackground(), contentAlignment = Alignment.Center) {
        when (variant) {
            ShortcutVariant.Button -> ShortcutButton(target, look, GlanceModifier.clickable(open))
            ShortcutVariant.Bar -> ComposeBar(target, barLook, GlanceModifier.fillMaxWidth().clickable(open))
            ShortcutVariant.TallBar -> ComposerBoxBar(target, barLook, GlanceModifier.fillMaxSize().clickable(open))
        }
    }
}

/**
 * The round button: a disc sized to its cell — the launcher icons' 56dp on the usual grids, never smaller than the
 * app's 44dp touch target — with the target's glyph at a little under half its width, as the composer's "+" sits
 * in its disc. Its ring is a second disc behind it, so the edge is one dp of the stroke colour.
 */
@Composable
private fun ShortcutButton(target: ShortcutTarget, look: ShortcutLook, modifier: GlanceModifier) {
    val cell = LocalSize.current
    val disc = (minOf(cell.width, cell.height) - 16.dp).coerceIn(44.dp, 64.dp)
    Disc(size = disc, glyph = target.glyph, contentDescription = target.label, fill = look.fill, ring = look.ring, tint = look.glyph, modifier = modifier)
}

/**
 * The compose bar: the composer's footer and its placeholder folded into one 48dp line — the "+" disc, the
 * placeholder in the composer's 14sp at the tertiary tone, the send disc at rest — on the composer's surface.
 */
@Composable
private fun ComposeBar(target: ShortcutTarget, look: ShortcutLook, modifier: GlanceModifier) {
    ComposerSurface(look, modifier.height(48.dp)) {
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            PlusDisc(target, look)
            Spacer(GlanceModifier.width(10.dp))
            Placeholder(target, look, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            SendDisc(look)
        }
    }
}

/**
 * The composer box as the New Chat pane draws it, on the home screen: the placeholder at the top, inset the way the
 * field is (12dp padding and the 4dp text inset), and the footer of round buttons along the bottom.
 */
@Composable
private fun ComposerBoxBar(target: ShortcutTarget, look: ShortcutLook, modifier: GlanceModifier) {
    ComposerSurface(look, modifier) {
        Column(GlanceModifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Placeholder(target, look, GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
                PlusDisc(target, look)
                Spacer(GlanceModifier.defaultWeight())
                SendDisc(look)
            }
        }
    }
}

/**
 * The composer's surface with its hairline: the stroke colour as the outer shape, and the fill one dp inside it,
 * concentric at the composer's 24dp radius. Drawn as coloured, rounded boxes from Android 12 — a colour keeps its
 * alpha, which the glass and app-tinted looks need — and as tinted shape drawables before that (see [rounded]).
 */
@Composable
private fun ComposerSurface(look: ShortcutLook, modifier: GlanceModifier, content: @Composable () -> Unit) {
    // Only ever called with a look that paints a surface (see ShortcutWidgetContent); glass stands in for icon-only.
    val fill = look.fill ?: return content()
    val ring = look.ring ?: fill
    Box(modifier.rounded(ring, ComposerRadius, R.drawable.widget_composer_surface), contentAlignment = Alignment.Center) {
        Box(GlanceModifier.fillMaxSize().padding(1.dp), contentAlignment = Alignment.Center) {
            Box(GlanceModifier.fillMaxSize().rounded(fill, ComposerRadius - 1.dp, R.drawable.widget_composer_surface_inner), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** `CursorDimens.composerRadius`: the 24dp disc's half plus the 12dp padding it sits in. */
private val ComposerRadius = 24.dp

/**
 * A [color] fill with [radius] corners. From Android 12 a plain colour background under a clipped outline, so a
 * translucent colour stays translucent. Before that a [fallback] shape drawable tinted to the colour: the tint is
 * SRC_ATOP, which keeps the drawable's own alpha — an opaque disc stays opaque — so the glass look degrades to a
 * solid one there, and the app-tinted look's 8 % discs to the surface's colour; the white look is unchanged.
 */
private fun GlanceModifier.rounded(color: ColorProvider, radius: Dp, fallback: Int): GlanceModifier =
    if (Build.VERSION.SDK_INT >= 31) {
        background(color).cornerRadius(radius)
    } else {
        background(ImageProvider(fallback), ContentScale.FillBounds, ColorFilter.tint(color))
    }

/** What the bar's field would ask: the composer's placeholder for a new chat, the destination's name otherwise. */
@Composable
private fun Placeholder(target: ShortcutTarget, look: ShortcutLook, modifier: GlanceModifier) {
    val text = when (target) {
        ShortcutTarget.NewChat -> COMPOSER_PLACEHOLDER
        ShortcutTarget.Search -> "Search chats"
        is ShortcutTarget.Chat -> "Open ${target.name}"
        is ShortcutTarget.Project -> "Open ${target.name}"
    }
    Text(text, style = TextStyle(color = look.text, fontSize = 14.sp), maxLines = 1, modifier = modifier)
}

/** The composer's "+" — or, for a bar pointed at a chat, a Project or search, that target's glyph in the same disc. */
@Composable
private fun PlusDisc(target: ShortcutTarget, look: ShortcutLook) {
    Disc(size = 24.dp, glyph = target.glyph, contentDescription = null, fill = look.disc, ring = null, tint = look.discGlyph, glyphSize = 17.dp)
}

/** The composer's send disc at rest — the 6 % fill and the 28 % glyph of a send with nothing to send yet. */
@Composable
private fun SendDisc(look: ShortcutLook) {
    Disc(size = 24.dp, glyph = R.drawable.widget_arrow_up, contentDescription = null, fill = look.sendDisc, ring = null, tint = look.sendGlyph, glyphSize = 17.dp)
}

/**
 * A disc with a glyph centred in it. [ring], when given, is drawn as a disc one dp larger behind the fill; a null
 * [fill] paints nothing at all (the icon-only look). Coloured and clipped round like [ComposerSurface], for the same reason.
 */
@Composable
private fun Disc(
    size: Dp,
    glyph: Int,
    contentDescription: String?,
    fill: ColorProvider?,
    ring: ColorProvider?,
    tint: ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
    glyphSize: Dp = (size.value * 0.46f).dp,
) {
    val outer = if (ring != null && fill != null) modifier.size(size).rounded(ring, size / 2, R.drawable.widget_disc) else modifier.size(size)
    Box(outer, contentAlignment = Alignment.Center) {
        val inner = when {
            fill == null -> GlanceModifier.size(size)
            ring != null -> GlanceModifier.size(size - 2.dp).rounded(fill, (size - 2.dp) / 2, R.drawable.widget_disc)
            else -> GlanceModifier.size(size).rounded(fill, size / 2, R.drawable.widget_disc)
        }
        Box(inner, contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(glyph),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(glyphSize),
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

/** The glyph a target is shown by: "+" for a new chat, the search glass, the sidebar's chat sparkle, the cube for a Project. */
private val ShortcutTarget.glyph: Int
    get() = when (this) {
        ShortcutTarget.NewChat -> R.drawable.widget_plus
        ShortcutTarget.Search -> R.drawable.widget_search
        is ShortcutTarget.Chat -> R.drawable.widget_sparkle
        is ShortcutTarget.Project -> R.drawable.widget_cube
    }

/**
 * The colours one [ShortcutStyle] paints with, as Glance providers. The widget sits on the wallpaper, so a look
 * decides what goes under the glyph: white keeps to white whatever the theme; the app tint takes the composer's own
 * surface, stroke and tones for the app's theme (a day / night pair under "Match system", like the Chats widget);
 * glass is a wash of the foreground the system theme would use — dark by day, white at night — with the wallpaper
 * through it; icon only paints nothing behind the glyph and lets the glyph itself follow day and night.
 *
 * The [WidgetAppearance] every widget of this app shares applies here too: its theme decides which colours the
 * app-tinted look takes (the app's own, or a pinned one), and its opacity is how much of the wallpaper shows through
 * the white and app-tinted discs and the glass wash (icon only has nothing to see through).
 *
 * Fills and text carry the app's translucent tokens as they are. Glyph tints do not: a RemoteViews image tint is
 * `setColorFilter`, SRC_ATOP over the drawable's white strokes, so a 28 % tint would come out nearly white. Each
 * glyph colour is therefore composited to an opaque colour over the surface it sits on ([over]), which is what
 * the token would have read as in the app.
 */
class ShortcutLook private constructor(
    /** Under the glyph; null paints nothing. */
    val fill: ColorProvider?,
    /** One dp around [fill]; null for no edge. */
    val ring: ColorProvider?,
    /** The button's glyph. Opaque. */
    val glyph: ColorProvider,
    /** The compose bar's placeholder. */
    val text: ColorProvider,
    /** The compose bar's "+" disc and its glyph (opaque). */
    val disc: ColorProvider,
    val discGlyph: ColorProvider,
    /** The compose bar's send disc at rest, and its glyph (opaque). */
    val sendDisc: ColorProvider,
    val sendGlyph: ColorProvider,
) {
    companion object {
        private val Dark = Color(0xFF141414)
        private val Light = Color(0xFFF0F0F0)

        /**
         * The look for [style] under [appearance]; [appMode] and [appOledBlack] are the app's own theme, which the
         * app-tinted look follows while the appearance says [WidgetTheme.App] (the other themes pin one).
         */
        fun of(style: ShortcutStyle, appearance: WidgetAppearance, appMode: ThemeMode, appOledBlack: Boolean): ShortcutLook {
            val opacity = appearance.opacityFraction
            return when (style) {
                ShortcutStyle.White -> onSurface(surface = Color.White, ring = Dark.copy(alpha = 0.08f), base = Dark, opacity = opacity)
                ShortcutStyle.Tinted -> when (appearance.theme) {
                    WidgetTheme.App -> tinted(appMode, appOledBlack, opacity)
                    WidgetTheme.System -> tinted(ThemeMode.System, oledBlack = false, opacity)
                    WidgetTheme.Light -> tinted(ThemeMode.Light, oledBlack = false, opacity)
                    WidgetTheme.Dark -> tinted(ThemeMode.Dark, oledBlack = false, opacity)
                    WidgetTheme.Oled -> tinted(ThemeMode.Dark, oledBlack = true, opacity)
                }
                ShortcutStyle.Glass -> glass(
                    fill = dayNight(Dark.copy(alpha = 0.12f * opacity), Light.copy(alpha = 0.20f * opacity)),
                    ring = dayNight(Dark.copy(alpha = 0.16f * opacity), Light.copy(alpha = 0.28f * opacity)),
                )
                // The bar has no surface of its own here, so its discs and text take the glass tones over the wallpaper.
                ShortcutStyle.IconOnly -> glass(fill = null, ring = null)
            }
        }

        /**
         * A fixed [surface] with everything on it derived from [base] at the app's alphas (text 60 %, icon 66 / 28 %,
         * fills 8 / 6 %); the glyphs composited over the discs they sit in. The surface itself is drawn at [opacity].
         */
        private fun onSurface(surface: Color, ring: Color, base: Color, opacity: Float): ShortcutLook {
            val disc = base.copy(alpha = 0.08f).over(surface)
            val sendDisc = base.copy(alpha = 0.06f).over(surface)
            return ShortcutLook(
                fill = ColorProvider(surface.copy(alpha = opacity)),
                ring = ColorProvider(ring),
                glyph = ColorProvider(base),
                text = ColorProvider(base.copy(alpha = 0.60f)),
                disc = ColorProvider(disc),
                discGlyph = ColorProvider(base.copy(alpha = 0.66f).over(disc)),
                sendDisc = ColorProvider(sendDisc),
                sendGlyph = ColorProvider(base.copy(alpha = 0.28f).over(sendDisc)),
            )
        }

        /**
         * The wallpaper showing through: translucent washes of the system theme's foreground, and — the wallpaper
         * being nobody's to know — glyphs in fixed opaque tones that read on either.
         */
        private fun glass(fill: ColorProvider?, ring: ColorProvider?) = ShortcutLook(
            fill = fill,
            ring = ring,
            glyph = dayNight(Dark, Light),
            text = dayNight(Dark.copy(alpha = 0.66f), Light.copy(alpha = 0.72f)),
            disc = dayNight(Dark.copy(alpha = 0.10f), Light.copy(alpha = 0.16f)),
            discGlyph = dayNight(Color(0xFF3A3A3A), Color(0xFFE0E0E0)),
            sendDisc = dayNight(Dark.copy(alpha = 0.08f), Light.copy(alpha = 0.12f)),
            sendGlyph = dayNight(Color(0xFF7A7A7A), Color(0xFFA8A8A8)),
        )

        /** The composer's own surface (`--cursor-editor`, at [opacity]) and tones for [theme]; "Match system" is a day / night pair. */
        private fun tinted(theme: ThemeMode, oledBlack: Boolean, opacity: Float): ShortcutLook {
            val dark = if (oledBlack) CursorOledColors else CursorDarkColors
            fun token(pick: (CursorColors) -> Color): ColorProvider = when (theme) {
                ThemeMode.Dark -> ColorProvider(pick(dark))
                ThemeMode.Light -> ColorProvider(pick(CursorLightColors))
                ThemeMode.System -> ColorProvider(day = pick(CursorLightColors), night = pick(dark))
            }
            return ShortcutLook(
                fill = token { it.elevated.copy(alpha = opacity) },
                ring = token { it.strokeSubtle },
                glyph = token { it.iconPrimary },
                text = token { it.textTertiary },
                disc = token { it.fill },
                discGlyph = token { it.iconSecondary.over(it.fill.over(it.elevated)) },
                sendDisc = token { it.fillSoft },
                sendGlyph = token { it.iconQuaternary.over(it.fillSoft.over(it.elevated)) },
            )
        }

        private fun dayNight(day: Color, night: Color): ColorProvider = ColorProvider(day = day, night = night)

        /** [this] composited over an opaque [background]: the colour the token reads as on that surface. */
        private fun Color.over(background: Color): Color {
            val a = alpha
            return Color(
                red = red * a + background.red * (1 - a),
                green = green * a + background.green * (1 - a),
                blue = blue * a + background.blue * (1 - a),
                alpha = 1f,
            )
        }
    }
}
