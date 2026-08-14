package com.agentdeck.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Secure Beta frame-timing harness. CI compiles this module but must not run it
 * against a device that holds the only copy of user conversations.
 */
@RunWith(AndroidJUnit4::class)
class ChatTranscriptScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scroll300Turns() {
        measureScroll(turnCount = 300)
    }

    @Test
    fun scroll50Turns() {
        measureScroll(turnCount = 50)
    }

    @Test
    fun scroll1000Turns() {
        measureScroll(turnCount = 1000)
    }

    private fun measureScroll(turnCount: Int) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            // Skip the profileinstaller install broadcast: Funtouch/Android 16
            // background-broadcast limits drop it on V2301A. speed-profile compile
            // still runs, so numbers stay same-device comparable.
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Disable,
                warmupIterations = 3,
            ),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = { openSyntheticTranscript(turnCount) },
        ) {
            scrollTranscript()
        }
    }
}

internal fun MacrobenchmarkScope.openSyntheticTranscript(turnCount: Int) {
    pressHome()
    startActivityAndWait(
        android.content.Intent().apply {
            setClassName(TARGET_PACKAGE, BENCHMARK_ACTIVITY)
            putExtra("turn_count", turnCount)
        },
    )
    device.wait(Until.hasObject(By.res(TARGET_PACKAGE, TRANSCRIPT_TAG)), 15_000)
}

internal fun MacrobenchmarkScope.scrollTranscript() {
    val list = device.findObject(By.res(TARGET_PACKAGE, TRANSCRIPT_TAG)) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) { list.fling(Direction.UP) }
    repeat(3) { list.fling(Direction.DOWN) }
    device.waitForIdle()
}
