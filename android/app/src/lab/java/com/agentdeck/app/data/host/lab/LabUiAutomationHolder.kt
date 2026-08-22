package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.LabUiAutomationExecutor

/** Lab a11y service 注册当前执行器；默认路由到三级后端（Shell > a11y > ReadOnly）。 */
object LabUiAutomationHolder {
    @JvmStatic
    @Volatile
    var executor: LabUiAutomationExecutor = LabInputBackendRouter
}
