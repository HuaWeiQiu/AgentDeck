package com.agentdeck.app.ui.chat

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownAnimations
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.launch

/**
 * Shared Markdown theme + components for Codex and pi (same visual language).
 */
@Composable
internal fun ChatMarkdownEnvironment(content: @Composable () -> Unit) {
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(content = model.content, node = model.node) { code, _, _ ->
                    SharedCodeBlockWithCopy(code = code)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(content = model.content, node = model.node) { code, _, _ ->
                    SharedCodeBlockWithCopy(code = code)
                }
            },
        )
    }
    val imageTransformer = remember { NoOpImageTransformerImpl() }
    CompositionLocalProvider(
        LocalMarkdownColors provides markdownColor(),
        LocalMarkdownTypography provides markdownTypography(),
        LocalMarkdownPadding provides markdownPadding(),
        LocalMarkdownDimens provides markdownDimens(),
        LocalImageTransformer provides imageTransformer,
        LocalMarkdownComponents provides components,
        LocalMarkdownAnimations provides markdownAnimations(animateTextSize = { this }),
        content = content,
    )
}

@Composable
internal fun ChatMarkdownDocumentBody(
    document: ChatMarkdownDocument,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalReferenceLinkHandler provides document.referenceLinkHandler,
    ) {
        Column(modifier = modifier.fillMaxWidth().padding(end = 8.dp)) {
            document.blocks.forEachIndexed { index, block ->
                MarkdownElement(
                    node = block.node,
                    components = LocalMarkdownComponents.current,
                    content = document.content,
                    includeSpacer = index > 0,
                    skipLinkDefinition = true,
                )
            }
        }
    }
}

@Composable
internal fun SharedCodeBlockWithCopy(code: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("AgentDeck code", code.trimEnd())),
                            )
                        }
                        Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                code.trimEnd(),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
