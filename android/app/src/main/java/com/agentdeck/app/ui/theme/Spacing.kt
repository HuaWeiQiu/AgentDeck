package com.agentdeck.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 轻量间距阶梯，替代各屏幕里散落的硬编码 padding/spacer 数值。
 * 仅收敛常用档位，非常用尺寸（图标大小、描边等）不纳入。
 */
object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}
