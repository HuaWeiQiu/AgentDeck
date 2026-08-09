package com.agentdeck.app.data.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import java.util.concurrent.ConcurrentHashMap

typealias TermuxCommandResult = RuntimeCommandResult

internal data class TermuxResultPayload(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val internalErrorCode: Int?,
    val internalErrorMessage: String,
    val stdoutOriginalLength: Int?,
    val stderrOriginalLength: Int?,
)

internal object TermuxResultInterpreter {
    private const val INTERNAL_RESULT_OK = -1

    fun interpret(payload: TermuxResultPayload?): Result<TermuxCommandResult> {
        if (payload == null) {
            return Result.failure(IllegalStateException("Termux 回调缺少 result Bundle"))
        }
        val internalErrorCode = payload.internalErrorCode
            ?: return Result.failure(IllegalStateException("Termux 回调缺少内部错误码"))
        if (internalErrorCode != INTERNAL_RESULT_OK) {
            val message = payload.internalErrorMessage.ifBlank {
                "Termux 内部错误: $internalErrorCode"
            }
            return Result.failure(IllegalStateException(message))
        }
        val exitCode = payload.exitCode
            ?: return Result.failure(IllegalStateException("Termux 回调缺少命令退出码"))
        return Result.success(
            TermuxCommandResult(
                stdout = payload.stdout,
                stderr = payload.stderr,
                exitCode = exitCode,
                stdoutOriginalLength = payload.stdoutOriginalLength,
                stderrOriginalLength = payload.stderrOriginalLength,
            ),
        )
    }
}

internal object TermuxResultRegistry {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<TermuxCommandResult>>>()

    fun register(requestId: String): CompletableDeferred<Result<TermuxCommandResult>> {
        val deferred = CompletableDeferred<Result<TermuxCommandResult>>()
        check(pending.putIfAbsent(requestId, deferred) == null) {
            "重复的 Termux 结果请求 ID"
        }
        return deferred
    }

    fun complete(requestId: String, result: Result<TermuxCommandResult>) {
        pending.remove(requestId)?.complete(result)
    }

    fun remove(requestId: String) {
        pending.remove(requestId)?.cancel()
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val requestId = intent?.data?.lastPathSegment ?: return
        val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        val payload = bundle?.let {
            TermuxResultPayload(
                stdout = it.getString(EXTRA_STDOUT).orEmpty(),
                stderr = it.getString(EXTRA_STDERR).orEmpty(),
                exitCode = it.getIntOrNull(EXTRA_EXIT_CODE),
                internalErrorCode = it.getIntOrNull(EXTRA_INTERNAL_ERROR_CODE),
                internalErrorMessage = it.getString(EXTRA_INTERNAL_ERROR_MESSAGE).orEmpty(),
                stdoutOriginalLength = it.getString(EXTRA_STDOUT_ORIGINAL_LENGTH)?.toIntOrNull(),
                stderrOriginalLength = it.getString(EXTRA_STDERR_ORIGINAL_LENGTH)?.toIntOrNull(),
            )
        }
        TermuxResultRegistry.complete(requestId, TermuxResultInterpreter.interpret(payload))
    }

    private fun android.os.Bundle.getIntOrNull(key: String): Int? {
        return if (containsKey(key)) getInt(key) else null
    }

    companion object {
        const val EXTRA_RESULT_BUNDLE = "result"
        const val EXTRA_STDOUT = "stdout"
        const val EXTRA_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        const val EXTRA_STDERR = "stderr"
        const val EXTRA_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
        const val EXTRA_EXIT_CODE = "exitCode"
        const val EXTRA_INTERNAL_ERROR_CODE = "err"
        const val EXTRA_INTERNAL_ERROR_MESSAGE = "errmsg"
    }
}
