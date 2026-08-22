package com.agentdeck.app.data.host.lab

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 连接管理：懒绑定、阻塞等待、exec 封装。
 * daemon(false)：App 侧断开后服务退出，不常驻 Shizuku server。
 */
internal object LabShizukuConnection {

    private const val BIND_TIMEOUT_MS = 4_000L
    private const val SERVICE_VERSION = 1

    @Volatile
    private var binder: IShellCommandService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var latch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = IShellCommandService.Stub.asInterface(service)
            binding = false
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 断连后允许下次调用重新绑定。
            binder = null
            binding = false
            latch = CountDownLatch(1)
        }
    }

    fun isAvailable(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 返回可用的服务 binder；未授权或绑定超时返回 null。
     * 调用方已在后台线程（snapshot/click 均为工具执行路径），内部只做短阻塞等待。
     */
    private fun serviceOrNull(): IShellCommandService? {
        binder?.let { return it }
        if (!isAvailable()) return null
        if (!binding) {
            binding = true
            try {
                val args = UserServiceArgs(
                    ComponentName(appPackageName(), LabShellCommandService::class.java.name),
                )
                    .daemon(false)
                    .version(SERVICE_VERSION)
                Shizuku.bindUserService(args, connection)
            } catch (e: Exception) {
                binding = false
                return null
            }
        }
        latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return binder
    }

    /** 执行命令；返回 JSON（exit/output 或 timeout/error），失败返回 null。 */
    fun exec(command: String): String? {
        val service = serviceOrNull() ?: return null
        return runCatching { service.exec(command) }.getOrNull()
    }

    /** UserServiceArgs 需要包名；无 Context 可用，经 ActivityThread 反射取当前 Application。 */
    private fun appPackageName(): String {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as android.app.Application
        return app.packageName
    }
}
