package com.agentdeck.app.ui.common

import com.agentdeck.app.domain.model.ProviderModel

/**
 * 可搜索模型下拉的过滤规则。
 *
 * 输入框会回显当前已选 model id；若此时仍按 contains 过滤，列表会被收成「只剩当前项」。
 * 因此当 query 为空或等于已选 id 时展示全部，仅在用户主动改写搜索词时再过滤。
 */
internal fun filterSelectableModels(
    models: List<ProviderModel>,
    query: String,
    selectedId: String?,
    maxVisible: Int = DEFAULT_MAX_VISIBLE_MODELS,
): List<ProviderModel> {
    val trimmed = query.trim()
    val showAll = trimmed.isEmpty() ||
        (selectedId != null && trimmed.equals(selectedId, ignoreCase = false))
    val filtered = if (showAll) {
        models
    } else {
        models.filter { model ->
            model.id.contains(trimmed, ignoreCase = true) ||
                model.displayName.contains(trimmed, ignoreCase = true)
        }
    }
    return if (maxVisible > 0) filtered.take(maxVisible) else filtered
}

internal const val DEFAULT_MAX_VISIBLE_MODELS = 100
