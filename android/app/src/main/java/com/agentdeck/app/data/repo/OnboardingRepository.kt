package com.agentdeck.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.agentdeck.app.domain.model.EnvironmentReport

class OnboardingRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun shouldOpenDoctor(): Boolean = !preferences.getBoolean(KEY_DOCTOR_COMPLETED, false)

    /** Runtime was previously verified good enough to launch sessions. */
    fun lastCanLaunchSessions(): Boolean = preferences.getBoolean(KEY_DOCTOR_COMPLETED, false)

    /** Runtime + model auth were both OK last scan. */
    fun lastFullyReady(): Boolean = preferences.getBoolean(KEY_FULLY_READY, false)

    fun shouldShowLocalDataNotice(): Boolean = !preferences.getBoolean(KEY_LOCAL_DATA_NOTICE, false)

    fun markLocalDataNoticeSeen() {
        preferences.edit { putBoolean(KEY_LOCAL_DATA_NOTICE, true) }
    }

    fun record(report: EnvironmentReport) {
        preferences.edit {
            if (report.canLaunchSessions) {
                putBoolean(KEY_DOCTOR_COMPLETED, true)
            }
            putBoolean(KEY_FULLY_READY, report.allCriticalOk)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_onboarding"
        private const val KEY_DOCTOR_COMPLETED = "doctor_completed"
        private const val KEY_FULLY_READY = "setup_fully_ready"
        private const val KEY_LOCAL_DATA_NOTICE = "local_data_notice_seen"
    }
}
