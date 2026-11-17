package com.example.giraffeaddiction.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.giraffeaddiction.ui.theme.Danger
import com.example.giraffeaddiction.ui.theme.EyebrowStyle
import com.example.giraffeaddiction.ui.theme.Amber
import com.example.giraffeaddiction.ui.theme.Ember
import com.example.giraffeaddiction.ui.theme.Moss
import com.example.giraffeaddiction.ui.theme.MonoStyle
import com.example.giraffeaddiction.ui.theme.Tones
import kotlinx.coroutines.delay

/** The house shapes: one radius for cards, pills for buttons. */
val CardShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(50)

/** A clock that ticks once a second, for countdowns. */
@Composable
fun rememberNow(periodMs: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The letter-spaced caps above a screen or section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = EyebrowStyle, color = Tones.faint, modifier = modifier)
}

/** The big serif line that names a screen or a state. */
@Composable
fun Headline(text: String, modifier: Modifier = Modifier, italic: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onBackground,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        modifier = modifier,
    )
}

@Composable
fun Lede(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tones.muted, modifier = modifier)
}

@Composable
fun ScreenHeader(eyebrow: String, title: String, lede: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(eyebrow)
        Spacer(Modifier.height(8.dp))
        Headline(title)
        if (lede != null) {
            Spacer(Modifier.height(8.dp))
            Lede(lede)
        }
    }
}

/** A white card with a hairline, no shadow: paper on bone. */
@Composable
fun GiraffeCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    tinted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = if (tinted) Tones.sand else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Tones.rule),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

enum class Tone { GOOD, WARN, BAD, LIVE, OFF }

fun Tone.color(): Color = when (this) {
    Tone.GOOD -> Moss
    Tone.WARN -> Amber
    Tone.BAD -> Danger
    Tone.LIVE -> Ember
    Tone.OFF -> Color(0xFFD3C5BE)
}

@Composable
fun StatusDot(tone: Tone, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.color()),
    )
}

/** A small pill label. */
@Composable
fun Chip(text: String, tone: Tone = Tone.OFF, modifier: Modifier = Modifier) {
    Surface(shape = PillShape, color = Tones.sand, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(tone, 7.dp)
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = Tones.sand,
            disabledContentColor = Tones.faint,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, if (danger) Danger.copy(alpha = 0.5f) else Tones.rule),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) Danger else MaterialTheme.colorScheme.onSurface,
            disabledContentColor = Tones.faint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The mascot on a soft disc, the one picture on a screen. */
@Composable
fun Mascot(resId: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 0.86f)
                .clip(CircleShape)
                .background(Tones.sand)
                .border(1.dp, Tones.rule, CircleShape),
        )
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier.size(size * 0.92f),
        )
    }
}

/** One line in a card: a label on the left, a value or control on the right. */
@Composable
fun InfoRow(
    label: String,
    value: String? = null,
    tone: Tone? = null,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone != null) {
            StatusDot(tone)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
                color = Tones.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1.2f),
            )
        }
        trailing?.invoke(this)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = Tones.rule, thickness = 1.dp)
}

/** A tappable row with an optional icon on the left and a chevron-less layout on the right. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tones.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun AppIcon(bitmap: ImageBitmap?, size: Dp = 44.dp) {
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(Tones.sand),
        )
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Outlined.Home),
    APPS("Apps", Icons.Outlined.Lock),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** A quiet bottom bar: three words, an ember dot under the one that is open. */
@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (tab in Tab.entries) {
                val active = tab == current
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) MaterialTheme.colorScheme.onBackground else Tones.faint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onBackground else Tones.faint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (active) Ember else Color.Transparent),
                    )
                }
            }
        }
    }
}
