package com.agentdeck.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates an app Baseline Profile from the Secure Beta package.
 * Do not run this against a phone that holds unique user data.
 */
@RunWith(AndroidJUnit4::class)
class ChatBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            startActivityAndWait()
            device.waitForIdle()
            openSyntheticTranscript(300)
            scrollTranscript()
        }
    }
}
