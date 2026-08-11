package com.agentdeck.app.domain.host

/**
 * 将用户/模型提供的相对路径规范为安全的工作区相对路径。
 * fail closed：任何逃逸或绝对路径 → null。
 */
object HostPathGuard {
    /**
     * @return 规范化后的相对路径（"" 表示工作区根）；非法时 null
     */
    fun normalizeRelative(raw: String?): String? {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "./") return ""
        if (trimmed.startsWith('/') || trimmed.startsWith('\\')) return null
        if (trimmed.contains('\u0000')) return null
        if (':' in trimmed) return null // reject Windows drive / scheme-like
        if (trimmed.startsWith("content:", ignoreCase = true) ||
            trimmed.startsWith("file:", ignoreCase = true)
        ) {
            return null
        }

        val parts = trimmed.replace('\\', '/').split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> return null
                part.contains('\u0000') -> return null
                else -> out.add(part)
            }
        }
        if (out.any { it == ".." }) return null
        return out.joinToString("/")
    }

    fun childRelative(parentNormalized: String, childName: String): String? {
        val name = childName.trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name ||
            name.contains('\u0000')
        ) {
            return null
        }
        return if (parentNormalized.isEmpty()) name else "$parentNormalized/$name"
    }
}
