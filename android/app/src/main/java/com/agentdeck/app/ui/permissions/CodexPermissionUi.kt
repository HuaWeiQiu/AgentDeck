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
            description = "可以查看和分析文件，但不会运行修改操作。",
            technicalSummary = "untrusted 审批；自动拒绝命令、文件修改和额外权限请求",
        )

        CodexPermissionLevel.ASK_FIRST -> CodexPermissionPresentation(
            title = "推荐",
            description = "读取可直接进行，修改文件或运行其他操作前会询问你。",
            technicalSummary = "untrusted 审批；PRoot 作为 external sandbox，命令网络可用",
        )

        CodexPermissionLevel.FULL_ACCESS -> CodexPermissionPresentation(
            title = "完全访问",
            description = "Codex 可以直接运行命令和修改文件，不再逐次询问。",
            technicalSummary = "never 审批；PRoot 作为 external sandbox，完整磁盘与网络访问",
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
