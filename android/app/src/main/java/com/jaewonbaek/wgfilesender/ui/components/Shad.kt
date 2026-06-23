package com.jaewonbaek.wgfilesender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaewonbaek.wgfilesender.ui.theme.Shad

enum class BtnVariant { Primary, Outline, Ghost, Destructive }

@Composable
fun ShadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val bg: Color; val fg: Color; val stroke: Color?
    when (variant) {
        BtnVariant.Primary -> { bg = Shad.primary; fg = Shad.primaryForeground; stroke = null }
        BtnVariant.Destructive -> { bg = Shad.destructive; fg = Color.White; stroke = null }
        BtnVariant.Outline -> { bg = Color.Transparent; fg = Shad.foreground; stroke = Shad.border }
        BtnVariant.Ghost -> { bg = Color.Transparent; fg = Shad.foreground; stroke = null }
    }
    val shape = RoundedCornerShape(Shad.radiusMd)
    Row(
        modifier = modifier
            .clip(shape)
            .then(if (stroke != null) Modifier.border(1.dp, stroke, shape) else Modifier)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ShadCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Shad.radiusLg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Shad.card)
            .border(1.dp, Shad.border, shape)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun ShadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val shape = RoundedCornerShape(Shad.radiusMd)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Shad.border, shape)
            .background(Shad.background)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        singleLine = true,
        textStyle = TextStyle(color = Shad.foreground, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, color = Shad.mutedForeground, fontSize = 14.sp)
                }
                inner()
            }
        }
    )
}

/** Segmented tab control, shadcn Tabs look. */
@Composable
fun ShadTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Shad.radiusMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Shad.muted)
            .padding(4.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Shad.radiusSm))
                    .background(if (active) Shad.background else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) Shad.foreground else Shad.mutedForeground,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}
