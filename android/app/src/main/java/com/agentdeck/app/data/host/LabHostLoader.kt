package com.agentdeck.app.data.host

import android.content.Context
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.host.LabIntentExecutor
import com.agentdeck.app.domain.host.LabPrivilegedExecutor
import com.agentdeck.app.domain.host.LabUiAutomationExecutor
import com.agentdeck.app.domain.host.NoOpLabIntentExecutor
import com.agentdeck.app.domain.host.NoOpLabPrivilegedExecutor
import com.agentdeck.app.domain.host.NoOpLabUiExecutor

/**
 * Secure 通道返回 NoOp；Lab 通道反射加载 lab source set 实现，避免 secure 编译依赖 lab 类型。
 */
object LabHostLoader {
    fun intentExecutor(context: Context): LabIntentExecutor {
        if (!BuildConfig.HOST_LAB) return NoOpLabIntentExecutor
        return runCatching {
            val clazz = Class.forName("com.agentdeck.app.data.host.lab.LabIntentExecutorImpl")
            clazz.getConstructor(Context::class.java).newInstance(context.applicationContext) as LabIntentExecutor
        }.getOrDefault(NoOpLabIntentExecutor)
    }

    fun uiExecutor(): LabUiAutomationExecutor {
        if (!BuildConfig.HOST_LAB) return NoOpLabUiExecutor
        return runCatching {
            val clazz = Class.forName("com.agentdeck.app.data.host.lab.LabUiAutomationHolder")
            val field = clazz.getDeclaredField("executor")
            field.isAccessible = true
            // Kotlin object INSTANCE
            val instance = clazz.getField("INSTANCE").get(null)
            field.get(instance) as LabUiAutomationExecutor
        }.getOrDefault(NoOpLabUiExecutor)
    }

    fun privExecutor(): LabPrivilegedExecutor {
        if (!BuildConfig.HOST_LAB) return NoOpLabPrivilegedExecutor
        return runCatching {
            val clazz = Class.forName("com.agentdeck.app.data.host.lab.LabPrivilegedExecutorImpl")
            clazz.getDeclaredConstructor().newInstance() as LabPrivilegedExecutor
        }.getOrDefault(NoOpLabPrivilegedExecutor)
    }
}
