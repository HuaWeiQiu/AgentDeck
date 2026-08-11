package com.agentdeck.app.data.host.lab

import com.agentdeck.app.domain.host.LabUiAutomationExecutor
import com.agentdeck.app.domain.host.NoOpLabUiExecutor

/** Lab a11y service 注册当前执行器，供 main 反射加载。 */
object LabUiAutomationHolder {
    @JvmStatic
    @Volatile
    var executor: LabUiAutomationExecutor = NoOpLabUiExecutor
}
