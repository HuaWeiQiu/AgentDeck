package com.agentdeck.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact multi-line chat input with text vertically centered against side icon buttons.
 * Avoids Material3 filled [androidx.compose.material3.TextField] min-height / padding
 * that leaves the placeholder sitting high above the control row midline.
 */
@Composable
fun ChatComposerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String,
    minHeight: Dp = 42.dp,
    maxLines: Int = 5,
) {
    val shape = RoundedCornerShape(minHeight / 2)
    val container = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (enabled) 0.55f else 0.3f,
    )
    val textColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.5f,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = maxLines,
        modifier = modifier
            .heightIn(min = minHeight)
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(container, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}
