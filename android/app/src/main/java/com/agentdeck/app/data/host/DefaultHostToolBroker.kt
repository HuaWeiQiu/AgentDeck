package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.DenyAllHostApprovalGateway
import com.agentdeck.app.domain.host.HostApprovalGateway
import com.agentdeck.app.domain.host.HostAuthToken
import com.agentdeck.app.domain.host.HostAuditEvent
import com.agentdeck.app.domain.host.HostCapability
import com.agentdeck.app.domain.host.HostLimits
import com.agentdeck.app.domain.host.HostPathGuard
import com.agentdeck.app.domain.host.HostToolBroker
import com.agentdeck.app.domain.host.HostToolCall
import com.agentdeck.app.domain.host.HostToolName
import com.agentdeck.app.domain.host.HostToolPolicy
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.WorkspaceDocumentStore
import com.agentdeck.app.domain.settings.ExperienceLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

class DefaultHostToolBroker(
    private val policyProvider: () -> HostToolPolicy,
    private val workspace: () -> WorkspaceDocumentStore?,
    private val approval: HostApprovalGateway = DenyAllHostApprovalGateway,
    private val auth: HostAuthService = HostAuthService(),
    private val auditLog: InMemoryHostAuditLog = InMemoryHostAuditLog(),
) : HostToolBroker {
    private val mutex = Mutex()

    override fun mintToken(
        conversationId: String,
        instanceId: String,
        nowEpochMs: Long,
    ): HostAuthToken = auth.mint(conversationId, instanceId, nowEpochMs)

    override fun listEnabledCapabilities(): Set<HostCapability> =
        policyProvider().listEnabledCapabilities()

    override fun recentAudit(): List<HostAuditEvent> = auditLog.snapshot()

    override suspend fun invoke(call: HostToolCall, nowEpochMs: Long): HostToolResult = mutex.withLock {
        val started = nowEpochMs
        val tool = HostToolName.fromWire(call.tool)
        if (tool == null) {
            return deny(call, null, "host_unknown_tool", "未知宿主工具", started, nowEpochMs)
        }

        auth.validate(call.auth, call.conversationId, call.instanceId, nowEpochMs)?.let { code ->
            return deny(call, tool, code, authMessage(code), started, nowEpochMs)
        }

        policyProvider().evaluate(tool)?.let { denied ->
            return auditDenied(call, tool, denied, started, nowEpochMs)
        }

        val store = workspace()
            ?: return deny(
                call,
                tool,
                "host_workspace_unavailable",
                "工作区存储不可用",
                started,
                nowEpochMs,
            )

        if (tool.isWrite) {
            val summary = writeSummary(tool, call.args)
            val allowed = approval.requestWriteApproval(call, tool, summary)
            if (!allowed) {
                return deny(call, tool, "host_write_denied", "已拒绝写入本机工作区", started, nowEpochMs)
            }
            // 审批后再次确认策略（grant 可能在等待期间被撤销）
            policyProvider().evaluate(tool)?.let { denied ->
                return auditDenied(call, tool, denied, started, System.currentTimeMillis())
            }
        }

        val result = runCatching { execute(tool, call.args, store) }
            .getOrElse { error ->
                HostToolResult.Error(
                    code = "host_exec_failed",
                    userMessage = error.message?.take(160) ?: "宿主工具执行失败",
                )
            }
        audit(call, tool, result, started, System.currentTimeMillis())
        result
    }

    private fun execute(
        tool: HostToolName,
        args: Map<String, String>,
        store: WorkspaceDocumentStore,
    ): HostToolResult {
        val path = HostPathGuard.normalizeRelative(args["path"])
            ?: return HostToolResult.Denied("host_path_invalid", "工作区路径无效")
        return when (tool) {
            HostToolName.WORKSPACE_LIST -> {
                val depth = args["depth"]?.toIntOrNull()?.coerceIn(1, HostLimits.MAX_RECURSION_DEPTH)
                    ?: 1
                store.list(path, depth, HostLimits.MAX_LIST_ENTRIES).fold(
                    onSuccess = { entries ->
                        val lines = entries.joinToString("\n") { entry ->
                            val kind = if (entry.isDirectory) "dir" else "file"
                            val size = entry.sizeBytes?.toString() ?: "-"
                            "$kind\t$size\t${entry.relativePath}"
                        }
                        val truncated = entries.size >= HostLimits.MAX_LIST_ENTRIES
                        HostToolResult.Ok(
                            payload = mapOf(
                                "count" to entries.size.toString(),
                                "entries" to lines,
                            ),
                            truncated = truncated,
                        )
                    },
                    onFailure = { HostToolResult.Error("host_list_failed", it.message ?: "列出目录失败") },
                )
            }
            HostToolName.WORKSPACE_READ -> {
                store.read(path, HostLimits.MAX_FILE_BYTES).fold(
                    onSuccess = { read ->
                        HostToolResult.Ok(
                            payload = mapOf(
                                "path" to path,
                                "encoding" to "base64",
                                "bytes" to Base64.getEncoder().encodeToString(read.bytes),
                                "size" to read.bytes.size.toString(),
                            ),
                            truncated = read.truncated,
                        )
                    },
                    onFailure = { HostToolResult.Error("host_read_failed", it.message ?: "读取失败") },
                )
            }
            HostToolName.WORKSPACE_WRITE -> {
                val encoding = args["encoding"] ?: "utf8"
                val content = args["content"]
                    ?: return HostToolResult.Denied("host_missing_content", "缺少写入内容")
                val bytes = when (encoding) {
                    "utf8" -> content.toByteArray(Charsets.UTF_8)
                    "base64" -> runCatching { Base64.getDecoder().decode(content) }
                        .getOrElse {
                            return HostToolResult.Denied("host_bad_encoding", "base64 内容无效")
                        }
                    else -> return HostToolResult.Denied("host_bad_encoding", "不支持的编码")
                }
                if (bytes.size > HostLimits.MAX_FILE_BYTES) {
                    return HostToolResult.Denied("host_file_too_large", "文件超过 2 MiB 限制")
                }
                store.write(path, bytes, HostLimits.MAX_FILE_BYTES).fold(
                    onSuccess = {
                        HostToolResult.Ok(mapOf("path" to path, "size" to bytes.size.toString()))
                    },
                    onFailure = { HostToolResult.Error("host_write_failed", it.message ?: "写入失败") },
                )
            }
            HostToolName.WORKSPACE_MKDIR -> {
                store.mkdir(path).fold(
                    onSuccess = { HostToolResult.Ok(mapOf("path" to path, "created" to "true")) },
                    onFailure = { HostToolResult.Error("host_mkdir_failed", it.message ?: "创建目录失败") },
                )
            }
            HostToolName.WORKSPACE_REMOVE -> {
                store.removeFile(path).fold(
                    onSuccess = { HostToolResult.Ok(mapOf("path" to path, "removed" to "true")) },
                    onFailure = { HostToolResult.Error("host_remove_failed", it.message ?: "删除失败") },
                )
            }
            HostToolName.WORKSPACE_STAT -> {
                store.stat(path).fold(
                    onSuccess = { entry ->
                        HostToolResult.Ok(
                            mapOf(
                                "path" to entry.relativePath,
                                "directory" to entry.isDirectory.toString(),
                                "size" to (entry.sizeBytes?.toString() ?: ""),
                            ),
                        )
                    },
                    onFailure = { HostToolResult.Error("host_stat_failed", it.message ?: "stat 失败") },
                )
            }
        }
    }

    private fun writeSummary(tool: HostToolName, args: Map<String, String>): String {
        val path = HostPathGuard.normalizeRelative(args["path"]) ?: "(无效路径)"
        return when (tool) {
            HostToolName.WORKSPACE_WRITE -> "写入工作区文件 $path"
            HostToolName.WORKSPACE_MKDIR -> "在工作区创建目录 $path"
            HostToolName.WORKSPACE_REMOVE -> "删除工作区文件 $path"
            else -> tool.wireName
        }
    }

    private fun authMessage(code: String): String = when (code) {
        "host_auth_expired" -> "宿主授权已过期，请重新开始会话工具"
        "host_auth_conversation_mismatch", "host_auth_instance_mismatch" -> "宿主授权与当前对话不匹配"
        else -> "宿主授权无效"
    }

    private fun deny(
        call: HostToolCall,
        tool: HostToolName?,
        code: String,
        message: String,
        started: Long,
        now: Long,
    ): HostToolResult.Denied {
        val denied = HostToolResult.Denied(code, message)
        audit(call, tool, denied, started, now)
        return denied
    }

    private fun auditDenied(
        call: HostToolCall,
        tool: HostToolName,
        denied: HostToolResult.Denied,
        started: Long,
        now: Long,
    ): HostToolResult.Denied {
        audit(call, tool, denied, started, now)
        return denied
    }

    private fun audit(
        call: HostToolCall,
        tool: HostToolName?,
        result: HostToolResult,
        started: Long,
        now: Long,
    ) {
        val outcome = when (result) {
            is HostToolResult.Ok -> "ok"
            is HostToolResult.Denied -> "denied"
            is HostToolResult.Error -> "error"
        }
        val code = when (result) {
            is HostToolResult.Ok -> null
            is HostToolResult.Denied -> result.code
            is HostToolResult.Error -> result.code
        }
        auditLog.append(
            HostAuditEvent(
                epochMs = now,
                tool = tool?.wireName ?: call.tool,
                capability = tool?.capability?.name ?: "UNKNOWN",
                outcome = outcome,
                code = code,
                conversationIdHash = InMemoryHostAuditLog.hashConversationId(call.conversationId),
                durationMs = (now - started).coerceAtLeast(0L),
            ),
        )
    }

    companion object {
        fun policyFrom(
            experienceLevel: ExperienceLevel,
            workspaceEnabled: Boolean,
            hasGrant: Boolean,
            maxHostLevel: Int = 1,
        ): HostToolPolicy = HostToolPolicy(
            experienceLevel = experienceLevel,
            workspaceEnabled = workspaceEnabled,
            hasWorkspaceGrant = hasGrant,
            maxHostLevel = maxHostLevel,
        )
    }
}
