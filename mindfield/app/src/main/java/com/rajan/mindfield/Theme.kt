package com.rajan.mindfield

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajan.mindfield.core.Category
import com.rajan.mindfield.core.Evidence

/** The app's two moods: warm field-guide paper by day, deep ink by night. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val brand: Color,
    val good: Color,
    val bad: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF5EFE4),
    surface = Color(0xFFFFFCF7),
    raised = Color(0xFFEFE6D6),
    ink = Color(0xFF1E1A15),
    muted = Color(0xFF6B6357),
    faint = Color(0xFF9F9686),
    line = Color(0x221E1A15),
    brand = Color(0xFF2F3A8F),
    good = Color(0xFF2E7D4F),
    bad = Color(0xFFB3412E),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF111217),
    surface = Color(0xFF1A1C23),
    raised = Color(0xFF242733),
    ink = Color(0xFFEFE8DB),
    muted = Color(0xFFA9A194),
    faint = Color(0xFF6F6A62),
    line = Color(0x26EFE8DB),
    brand = Color(0xFFA9B4FF),
    good = Color(0xFF7FD3A0),
    bad = Color(0xFFF29A86),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Each corner of psychology has its own colour, like the tabs of a field guide. */
object Palettes {
    private data class Tones(val light: Long, val dark: Long, val plate: Long, val plateEnd: Long, val emoji: String)

    private val sets = mapOf(
        Category.MEMORY to Tones(0xFF3D6FA3, 0xFF8DBBEA, 0xFF1D3757, 0xFF2F5C8C, "👁"),
        Category.SELF to Tones(0xFF8A4C9C, 0xFFD6A1EA, 0xFF3A1F45, 0xFF6B3B7E, "👤"),
        Category.THINKING to Tones(0xFF4A55B5, 0xFFA3ABF7, 0xFF20255C, 0xFF3A44A0, "🧩"),
        Category.INFLUENCE to Tones(0xFFC0533A, 0xFFF2967E, 0xFF55200F, 0xFFA3442D, "📣"),
        Category.FEELINGS to Tones(0xFFC2456E, 0xFFF59AB8, 0xFF561731, 0xFF9E3559, "🌊"),
        Category.CONNECTION to Tones(0xFFC07016, 0xFFF6B25E, 0xFF553006, 0xFFA2600F, "🤝"),
        Category.DECISIONS to Tones(0xFF1D817A, 0xFF67D4C9, 0xFF0C3936, 0xFF18706A, "⚖️"),
        Category.HABITS to Tones(0xFF4F8A35, 0xFFA2D987, 0xFF213E18, 0xFF3F7029, "🔁"),
        Category.GROUPS to Tones(0xFFA0800F, 0xFFEBCB62, 0xFF463707, 0xFF8A6E12, "👥"),
    )

    fun accent(c: Category, dark: Boolean): Color = Color(sets.getValue(c).let { if (dark) it.dark else it.light })
    fun plate(c: Category): Color = Color(sets.getValue(c).plate)
    fun plateEnd(c: Category): Color = Color(sets.getValue(c).plateEnd)
    fun glow(c: Category): Color = Color(sets.getValue(c).dark)
    fun emoji(c: Category): String = sets.getValue(c).emoji

    fun evidence(e: Evidence, dark: Boolean): Color = when (e) {
        Evidence.SOLID -> if (dark) Color(0xFF7FD3A0) else Color(0xFF2E7D4F)
        Evidence.GOOD -> if (dark) Color(0xFF9CC4F2) else Color(0xFF2F6AA8)
        Evidence.DEBATED -> if (dark) Color(0xFFF0C46A) else Color(0xFF9A6A00)
        Evidence.BUSTED -> if (dark) Color(0xFFF29A86) else Color(0xFFB3412E)
    }
}

val Fraunces = FontFamily(
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val base = Typography()
private val AppType = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Inter, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Inter, fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelSmall = base.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

@Composable
fun MindfieldTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = remember(dark) {
        if (dark) {
            darkColorScheme(
                primary = p.brand, onPrimary = p.bg, background = p.bg, onBackground = p.ink, surface = p.surface,
                onSurface = p.ink, surfaceVariant = p.raised, onSurfaceVariant = p.muted, outline = p.line,
                outlineVariant = p.line, surfaceContainerHighest = p.raised, error = p.bad,
            )
        } else {
            lightColorScheme(
                primary = p.brand, onPrimary = Color.White, background = p.bg, onBackground = p.ink, surface = p.surface,
                onSurface = p.ink, surfaceVariant = p.raised, onSurfaceVariant = p.muted, outline = p.line,
                outlineVariant = p.line, surfaceContainerHighest = p.raised, error = p.bad,
            )
        }
    }
    CompositionLocalProvider(LocalPalette provides p, LocalContentColor provides p.ink) {
        MaterialTheme(colorScheme = scheme, typography = AppType, content = content)
    }
}

val palette: Palette @Composable get() = LocalPalette.current

fun Category.accent(dark: Boolean) = Palettes.accent(this, dark)

/** A light tap under the finger. */
fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/** The basic card: a slightly raised sheet of paper. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    border: Color? = null,
    background: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = palette
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(background ?: p.surface)
            .border(1.dp, border ?: p.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Small uppercase label above a section, with an emoji marker. */
@Composable
fun SectionLabel(text: String, emoji: String? = null, color: Color = palette.muted) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (emoji != null) Text(emoji, fontSize = 13.sp)
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = if (filled) Color.White else color,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
    )
}

/** Evidence as a little strength meter: three bars for Solid down to a cross for Busted. */
@Composable
fun EvidenceBadge(evidence: Evidence, onPlate: Boolean = false, modifier: Modifier = Modifier) {
    val p = palette
    val color = if (onPlate) Color.White else Palettes.evidence(evidence, p.dark)
    val bg = if (onPlate) Color.White.copy(alpha = 0.16f) else color.copy(alpha = 0.13f)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EvidenceMeter(evidence, color)
        Text(evidence.label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EvidenceMeter(evidence: Evidence, color: Color, modifier: Modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (evidence == Evidence.BUSTED) {
            val s = Stroke(width = h * 0.2f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.2f, h * 0.1f), Offset(w * 0.8f, h * 0.9f), s.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.8f, h * 0.1f), Offset(w * 0.2f, h * 0.9f), s.width, StrokeCap.Round)
            return@Canvas
        }
        val filled = when (evidence) {
            Evidence.SOLID -> 3
            Evidence.GOOD -> 2
            else -> 1
        }
        val bar = w / 4.2f
        for (i in 0 until 3) {
            val bh = h * (0.45f + 0.275f * i)
            drawRoundRect(
                color.copy(alpha = if (i < filled) 1f else 0.28f),
                Offset(i * bar * 1.6f, h - bh),
                Size(bar, bh),
                CornerRadius(bar / 2),
            )
        }
    }
}

/** The main action: a solid pill with a slight give under the finger. */
@Composable
fun PrimaryButton(text: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(27.dp))
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button) { view.tick(); onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, color: Color = palette.ink, onClick: () -> Unit) {
    val view = LocalView.current
    val p = palette
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(p.raised)
            .clickable(role = Role.Button) { view.tick(); onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A selectable chip; selected chips fill with their colour. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = palette
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier
            .clip(shape)
            .background(if (selected) color else p.surface)
            .border(1.dp, if (selected) color else p.line, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (selected) Color.White else p.ink,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
    )
}

/** Seven dots for this week, Monday first. */
@Composable
fun WeekDots(week: List<Pair<java.time.LocalDate, Boolean?>>, color: Color, modifier: Modifier = Modifier) {
    val p = palette
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEach { (day, done) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            when (done) {
                                true -> color
                                false -> p.raised
                                null -> Color.Transparent
                            },
                        )
                        .border(1.dp, if (done == null) p.line else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done == true) Text("✓", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = p.faint,
                )
            }
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.size(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

/** A short headline + muted body pair. */
@Composable
fun Headline(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
    }
}

val Quote = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 26.sp)

/** Paper texture: two very soft pools of colour so the background never looks flat. */
fun Modifier.paper(p: Palette, tint: Color): Modifier = this.background(p.bg).background(
    Brush.radialGradient(
        listOf(tint.copy(alpha = if (p.dark) 0.10f else 0.07f), Color.Transparent),
        center = Offset(0f, 0f),
        radius = 1400f,
    ),
)

fun Modifier.fullWidthPadding() = this.fillMaxWidth().padding(horizontal = 20.dp)
