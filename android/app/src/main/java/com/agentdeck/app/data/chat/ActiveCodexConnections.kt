package com.agentdeck.app.data.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级的活跃 Codex 连接登记表：聊天界面建立 CodexRpcClient 后按 cardId 注册，
 * 断开/退出时注销。会话列表的归档/重命名协议同步据此判断某卡片当前是否有
 * 活跃连接——没有则跳过同步，绝不为此拉起新的 runtime 或 app-server 进程。
 */
object ActiveCodexConnections {
    private val clients = ConcurrentHashMap<String, CodexRpcClient>()

    fun register(cardId: String, client: CodexRpcClient) {
        clients[cardId] = client
    }

    fun unregister(cardId: String, client: CodexRpcClient) {
        clients.remove(cardId, client)
    }

    fun clientFor(cardId: String): CodexRpcClient? = clients[cardId]
}
