package com.agentdeck.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.agentdeck.app.domain.model.EnvironmentReport

class OnboardingRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun shouldOpenDoctor(): Boolean = !preferences.getBoolean(KEY_DOCTOR_COMPLETED, false)

    fun record(report: EnvironmentReport) {
        if (report.canLaunchSessions) {
            preferences.edit { putBoolean(KEY_DOCTOR_COMPLETED, true) }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_onboarding"
        private const val KEY_DOCTOR_COMPLETED = "doctor_completed"
    }
}
