package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.LabUiAutomationExecutor

/** 无障碍后端运行时注册点；LabAccessibilityService 连接时写入，销毁时清除。 */
object LabAccessibilityRegistry {
    @Volatile
    var a11yExecutor: LabUiAutomationExecutor? = null
}
