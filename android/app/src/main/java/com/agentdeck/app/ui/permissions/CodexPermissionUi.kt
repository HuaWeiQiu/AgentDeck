package com.agentdeck.app.ui.permissions

import com.agentdeck.app.domain.model.CodexPermissionLevel

data class CodexPermissionPresentation(
    val title: String,
    val description: String,
    val technicalSummary: String,
)

fun codexPermissionPresentation(level: CodexPermissionLevel): CodexPermissionPresentation =
    when (level) {
        CodexPermissionLevel.READ_ONLY -> CodexPermissionPresentation(
            title = "只读",
            description = "只看文件，不改、不跑命令",
            technicalSummary = "自动拒绝改文件与命令",
        )

        CodexPermissionLevel.ASK_FIRST -> CodexPermissionPresentation(
            title = "推荐",
            description = "读文件直接做，改文件或跑命令前会问你",
            technicalSummary = "改动前询问",
        )

        CodexPermissionLevel.FULL_ACCESS -> CodexPermissionPresentation(
            title = "完全访问",
            description = "可直接改文件和跑命令",
            technicalSummary = "不再逐次询问",
        )
    }

fun permissionSelectionLabel(
    override: CodexPermissionLevel?,
    default: CodexPermissionLevel,
): String = if (override == null) {
    "使用默认 · ${codexPermissionPresentation(default).title}"
} else {
    codexPermissionPresentation(override).title
}
