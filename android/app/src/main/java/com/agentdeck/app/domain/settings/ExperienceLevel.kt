package com.agentdeck.app.domain.settings

enum class ExperienceLevel {
    STANDARD,
    ADVANCED,
    DEVELOPER,
    ;

    val advancedEnabled: Boolean
        get() = this != STANDARD

    companion object {
        fun fromStorage(value: String?): ExperienceLevel = entries
            .firstOrNull { it.name == value }
            ?: STANDARD
    }
}
