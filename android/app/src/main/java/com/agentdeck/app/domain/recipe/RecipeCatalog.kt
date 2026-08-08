package com.agentdeck.app.domain.recipe

import com.agentdeck.app.domain.model.AgentRecipe

interface RecipeCatalog {
    fun loadRecipes(): List<AgentRecipe>
    fun readWrapperAsset(name: String): String
}

object RecipeDependencyResolver {
    fun resolve(recipes: List<AgentRecipe>, targetId: String): Result<List<AgentRecipe>> = runCatching {
        val byId = recipes.associateBy { it.id }
        require(byId.size == recipes.size) { "配方 ID 重复" }
        require(byId.containsKey(targetId)) { "未知配方: $targetId" }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<AgentRecipe>()

        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "配方依赖存在循环: $id" }
            val recipe = requireNotNull(byId[id]) { "缺少依赖配方: $id" }
            recipe.dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
            ordered.add(recipe)
        }

        visit(targetId)
        ordered
    }
}
