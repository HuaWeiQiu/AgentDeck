package com.agentdeck.app.ui.common

import com.agentdeck.app.domain.model.ProviderModel

/**
 * 模型下拉可选项。
 *
 * 有发现列表时应只读选择；query 仅在需要时做过滤。query 为空或等于已选 id 时展示全部，
 * 避免把列表收成「只剩当前项」。
 */
internal fun filterSelectableModels(
    models: List<ProviderModel>,
    query: String = "",
    selectedId: String? = null,
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
