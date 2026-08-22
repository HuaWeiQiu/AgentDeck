package com.agentdeck.app.domain.host

/**
 * `input text`（shell 路径）的文本安全判断与转义。
 * input text 对非 ASCII（中文等）不可靠，且空格需转义为 %s、部分 shell 元字符需剔除。
 */
object ShellTextInput {

    /** 可经 `input text` 精确写入：仅可见 ASCII 且不含需要引号包裹的 shell 元字符。 */
    fun isAsciiSafe(value: String): Boolean {
        return value.all { it.code in 0x20..0x7e && it != '\'' && it != '"' && it != '`' && it != '\\' && it != '$' && it != ';' && it != '&' && it != '|' && it != '<' && it != '>' && it != '(' && it != ')' && it != '!' }
    }

    /** 空格转义为 %s；其余字符已在 isAsciiSafe 白名单内。 */
    fun escapeForInputText(value: String): String {
        return value.replace(" ", "%s")
    }

    /**
     * setText 决策：非 ASCII 或含元字符时返回 null（调用方应回退到无障碍后端或提示），
     * 否则返回转义后的安全文本。
     */
    fun safeArgumentOrNull(value: String): String? {
        if (value.isEmpty()) return null
        return value.takeIf(::isAsciiSafe)?.let(::escapeForInputText)
    }
}
