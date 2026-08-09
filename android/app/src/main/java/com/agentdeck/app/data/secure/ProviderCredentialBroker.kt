package com.agentdeck.app.data.secure

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProviderCredentialBroker(
    private val vault: ProviderCredentialVault,
    private val credentialRef: String,
    private val requestLimit: Int = DEFAULT_REQUEST_LIMIT,
) : AutoCloseable {
    private val expectedToken = AtomicReference<ByteArray?>(null)
    private val closed = AtomicBoolean(false)
    private val requests = AtomicInteger(0)

    init {
        require(requestLimit in 1..MAX_REQUEST_LIMIT) { "凭据请求次数无效" }
        EncryptedProviderCredentialVault.validateCredentialRef(credentialRef)
    }

    private val server = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "agentdeck-credential-broker").apply { isDaemon = true }
    }

    val port: Int = server.localPort

    init {
        executor.execute(::acceptLoop)
    }

    fun authorize(token: String) {
        require(token.matches(TOKEN_PATTERN)) { "凭据代理返回了无效授权令牌" }
        val next = token.toByteArray(StandardCharsets.US_ASCII)
        if (!expectedToken.compareAndSet(null, next)) {
            next.fill(0)
            error("凭据代理已经完成授权")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        expectedToken.getAndSet(null)?.fill(0)
        runCatching { server.close() }
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get() && requests.get() < requestLimit) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            socket.use(::serve)
        }
        close()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        requests.incrementAndGet()
        val response = runCatching {
            require(socket.inetAddress.isLoopbackAddress) { "非本机请求" }
            val line = readBoundedLine(socket)
            val request = JSONObject(line)
            require(request.length() == 2) { "请求字段无效" }
            require(request.getString("credential_ref") == credentialRef) { "凭据引用不匹配" }
            val supplied = request.getString("token").toByteArray(StandardCharsets.US_ASCII)
            val expected = expectedToken.get()
            val authorized = try {
                expected != null && MessageDigest.isEqual(expected, supplied)
            } finally {
                supplied.fill(0)
            }
            require(authorized) { "授权令牌不匹配" }
            val secret = requireNotNull(vault.load(credentialRef)) { "API Key 不存在" }
            try {
                JSONObject()
                    .put("ok", true)
                    .put("api_key_b64", Base64.getEncoder().encodeToString(secret))
                    .toString()
            } finally {
                secret.fill(0)
            }
        }.getOrElse {
            JSONObject().put("ok", false).toString()
        }
        socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.write(response)
            output.newLine()
        }
    }

    private fun readBoundedLine(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= MAX_REQUEST_BYTES) {
            val value = input.read()
            require(value >= 0) { "请求不完整" }
            if (value == '\n'.code) break
            bytes.write(value)
        }
        require(bytes.size() in 1..MAX_REQUEST_BYTES) { "请求长度无效" }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MILLIS = 5_000
        private const val MAX_REQUEST_BYTES = 2 * 1_024
        private const val DEFAULT_REQUEST_LIMIT = 16
        private const val MAX_REQUEST_LIMIT = 64
        private val TOKEN_PATTERN = Regex("[a-f0-9]{64}")
    }
}
