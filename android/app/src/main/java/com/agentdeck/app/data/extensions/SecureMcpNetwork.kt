package com.agentdeck.app.data.extensions

import com.agentdeck.app.data.secure.ExtensionCredentialVault
import com.agentdeck.app.domain.extensions.ExtensionPolicy
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ExtensionToolAccess
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (ExtensionPolicy.isPrivateLiteral(hostname)) throw UnknownHostException("private address")
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw UnknownHostException("private or non-routable address")
        }
        return addresses
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        return when (address) {
            is Inet4Address -> {
                val bytes = address.address.map(Byte::toInt).map { it and 0xff }
                bytes[0] == 0 || bytes[0] == 100 && bytes[1] in 64..127 ||
                    bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 0 ||
                    bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 2 ||
                    bytes[0] == 198 && bytes[1] in 18..19 ||
                    bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 ||
                    bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113
            }
            is Inet6Address -> {
                val first = address.address[0].toInt() and 0xff
                first and 0xfe == 0xfc
            }
            else -> true
        }
    }
}

internal fun secureMcpHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(PublicOnlyDns)
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

internal class SecureMcpProxy(
    private val upstream: HttpUrl,
    credentialVault: ExtensionCredentialVault,
    credentialRef: String?,
    private val client: OkHttpClient = secureMcpHttpClient(),
) : AutoCloseable {
    init {
        require(upstream.isHttps) { "MCP 代理只允许固定 HTTPS 上游" }
    }

    private val closed = AtomicBoolean(false)
    private val bearerLock = Any()
    private val sessionBearer = credentialRef?.let { ref ->
        requireNotNull(credentialVault.load(ref)) { "MCP Token 不存在" }
    }
    private val capability = randomToken()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val streamPermit = Semaphore(MAX_STREAMING_CONNECTIONS)
    private val executor = ThreadPoolExecutor(
        MAX_CONNECTIONS,
        MAX_CONNECTIONS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_CONNECTIONS),
        { task -> Thread(task, "agentdeck-mcp-proxy").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val acceptThread = Thread(::acceptLoop, "agentdeck-mcp-accept").apply {
        isDaemon = true
        start()
    }

    val url: String = "http://127.0.0.1:${server.localPort}/$capability"
    internal val trackedSocketCount: Int
        get() = synchronized(sockets) { sockets.size }
    internal val trackedCallCount: Int
        get() = synchronized(calls) { calls.size }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        synchronized(calls) {
            calls.toList().also { calls.clear() }
        }.forEach(Call::cancel)
        synchronized(sockets) { sockets.toList() }.forEach { socket -> runCatching(socket::close) }
        executor.shutdownNow()
        acceptThread.interrupt()
        synchronized(bearerLock) { sessionBearer?.fill(0) }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            sockets += socket
            runCatching {
                executor.execute {
                    try {
                        runCatching { socket.use(::serve) }
                    } finally {
                        sockets -= socket
                    }
                }
            }
                .onFailure {
                    sockets -= socket
                    runCatching(socket::close)
                }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = CLIENT_SOCKET_TIMEOUT_MILLIS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES)
        val parts = requestLine.split(' ')
        require(parts.size == 3 && parts[2] == "HTTP/1.1") { "invalid request" }
        val method = parts[0]
        require(method == "GET" || method == "POST" || method == "DELETE") { "unsupported method" }
        val streamLease = method == "GET" && streamPermit.tryAcquire()
        require(method != "GET" || streamLease) { "too many MCP streams" }
        try {
            forward(socket, input, method, parts[1])
        } finally {
            if (streamLease) streamPermit.release()
        }
    }

    private fun forward(
        socket: Socket,
        input: BufferedInputStream,
        method: String,
        target: String,
    ) {
        require(target.substringBefore('?') == "/$capability") { throw FileNotFoundException() }
        val headers = readHeaders(input)
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..MAX_REQUEST_BODY_BYTES) { "request too large" }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            require(count > 0) { "incomplete request" }
            offset += count
        }
        val upstreamUrl = upstream.newBuilder().apply {
            val query = target.substringAfter('?', "")
            if (query.isNotEmpty()) encodedQuery(query)
        }.build()
        val request = Request.Builder().url(upstreamUrl).apply {
            headers["accept"]?.let { header("Accept", it.take(MAX_HEADER_VALUE_BYTES)) }
            headers["content-type"]?.let { header("Content-Type", it.take(MAX_HEADER_VALUE_BYTES)) }
            headers["mcp-protocol-version"]?.let { header("MCP-Protocol-Version", it) }
            headers["mcp-session-id"]?.let { header("MCP-Session-Id", it) }
            headers["last-event-id"]?.let { header("Last-Event-ID", it) }
            authorizationHeader()?.let { header("Authorization", it) }
            val mediaType = headers["content-type"]?.toMediaType()
                ?: "application/json".toMediaType()
            when (method) {
                "POST" -> post(body.toRequestBody(mediaType))
                "DELETE" -> delete(if (body.isEmpty()) null else body.toRequestBody(mediaType))
                else -> get()
            }
        }.build()
        body.fill(0)
        val call = client.newCall(request)
        when (method) {
            "POST" -> call.timeout().timeout(UPSTREAM_POST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            "DELETE" -> call.timeout().timeout(UPSTREAM_DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val registered = synchronized(calls) {
            if (closed.get()) false else calls.add(call)
        }
        if (!registered) {
            call.cancel()
            error("MCP 代理已关闭")
        }
        try {
            call.execute().use { response ->
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())
                listOf("Content-Type", "MCP-Session-Id", "Cache-Control").forEach { name ->
                    response.header(name)?.take(MAX_HEADER_VALUE_BYTES)?.let { value ->
                        output.write("$name: $value\r\n".toByteArray())
                    }
                }
                output.write("Connection: close\r\n\r\n".toByteArray())
                val source = response.body?.byteStream()
                if (source != null) copyBounded(source, output, MAX_RESPONSE_BODY_BYTES)
                output.flush()
            }
        } finally {
            calls -= call
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES)
            total += line.length
            require(total <= MAX_HEADERS_BYTES) { "headers too large" }
            if (line.isEmpty()) return result
            val separator = line.indexOf(':')
            require(separator in 1 until line.lastIndex) { "invalid header" }
            val name = line.take(separator).trim().lowercase()
            val value = line.drop(separator + 1).trim()
            require(name.matches(HEADER_NAME_PATTERN) && value.none(Char::isISOControl)) {
                "invalid header"
            }
            if (name in FORWARDED_REQUEST_HEADERS) result[name] = value
        }
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val value = input.read()
            require(value >= 0) { "incomplete request" }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        require(bytes.size() <= limit) { "line too long" }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(16 * 1_024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "MCP 响应过大" }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
    }

    private fun authorizationHeader(): String? = synchronized(bearerLock) {
        sessionBearer?.let { secret ->
            check(!closed.get()) { "MCP 代理已关闭" }
            "Bearer ${String(secret, StandardCharsets.UTF_8)}"
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 4
        private const val MAX_PENDING_CONNECTIONS = 4
        private const val MAX_STREAMING_CONNECTIONS = 1
        private const val CLIENT_SOCKET_TIMEOUT_MILLIS = 120_000
        private const val UPSTREAM_POST_TIMEOUT_SECONDS = 95L
        private const val UPSTREAM_DELETE_TIMEOUT_SECONDS = 10L
        private const val MAX_REQUEST_LINE_BYTES = 4 * 1_024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1_024
        private const val MAX_HEADERS_BYTES = 32 * 1_024
        private const val MAX_HEADER_VALUE_BYTES = 4 * 1_024
        private const val MAX_REQUEST_BODY_BYTES = 2 * 1_024 * 1_024
        private const val MAX_RESPONSE_BODY_BYTES = 16L * 1_024 * 1_024
        private val HEADER_NAME_PATTERN = Regex("[a-z0-9-]{1,80}")
        private val FORWARDED_REQUEST_HEADERS = setOf(
            "accept",
            "content-type",
            "content-length",
            "mcp-protocol-version",
            "mcp-session-id",
            "last-event-id",
        )

        private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

class RemoteMcpToolDiscovery(
    private val policy: ExtensionPolicy,
    private val client: OkHttpClient = secureMcpHttpClient(),
    private val totalTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(DISCOVERY_TOTAL_TIMEOUT_SECONDS),
) {
    init {
        require(totalTimeoutMillis in 1..MAX_CONFIGURED_DISCOVERY_TIMEOUT_MILLIS)
    }

    private val discoveryClient = client.newBuilder()
        .readTimeout(DISCOVERY_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(totalTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    fun discover(url: String, bearer: ByteArray? = null): List<ExtensionTool> {
        val endpoint = policy.validateRemoteUrl(url)
        val sessionId = arrayOfNulls<String>(1)
        var protocolVersion: String? = null
        var totalResponseBytes = 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis)
        val consumeBytes: (Long) -> Unit = { count ->
            totalResponseBytes += count
            require(totalResponseBytes <= MAX_DISCOVERY_TOTAL_BYTES) { "MCP 发现响应总量过大" }
        }
        try {
            val initialize = rpc(
                endpoint,
                id = 1,
                method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", REQUESTED_PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "agentdeck").put("version", "1")),
                bearer = bearer,
                sessionId = null,
                onSession = { sessionId[0] = it },
                consumeBytes = consumeBytes,
                deadlineNanos = deadlineNanos,
            )
            val initializeResult = initialize.optJSONObject("result")
                ?: error("MCP 服务没有完成初始化")
            val negotiatedProtocolVersion = initializeResult.optString("protocolVersion")
                .takeIf { it in SUPPORTED_PROTOCOL_VERSIONS }
                ?: error("MCP 服务返回了不支持的协议版本")
            protocolVersion = negotiatedProtocolVersion
            notifyInitialized(endpoint, bearer, sessionId[0], negotiatedProtocolVersion, deadlineNanos)

            val now = System.currentTimeMillis()
            val discovered = mutableListOf<ExtensionTool>()
            val names = hashSetOf<String>()
            val cursors = hashSetOf<String>()
            var cursor: String? = null
            var requestId = 2
            var pageCount = 0
            do {
                pageCount += 1
                require(pageCount <= MAX_DISCOVERY_PAGES) { "MCP 工具分页过多" }
                val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
                val listed = rpc(
                    endpoint,
                    id = requestId++,
                    method = "tools/list",
                    params = params,
                    bearer = bearer,
                    sessionId = sessionId[0],
                    protocolVersion = negotiatedProtocolVersion,
                    consumeBytes = consumeBytes,
                    deadlineNanos = deadlineNanos,
                )
                val result = listed.optJSONObject("result")
                    ?: error("MCP 服务没有返回工具列表")
                val tools = result.optJSONArray("tools")
                    ?: error("MCP 服务没有返回工具列表")
                require(discovered.size + tools.length() <= MAX_TOOLS) {
                    "MCP 工具数量超过 $MAX_TOOLS 个"
                }
                for (index in 0 until tools.length()) {
                    val tool = tools.optJSONObject(index) ?: continue
                    val name = tool.optString("name").trim()
                    require(name.matches(TOOL_NAME_PATTERN)) { "MCP 返回了无效工具名称" }
                    require(names.add(name)) { "MCP 返回了重复工具名称: $name" }
                    val annotations = tool.optJSONObject("annotations")
                    val readOnly = annotations?.optBoolean("readOnlyHint", false) == true &&
                        annotations?.optBoolean("destructiveHint", false) != true
                    discovered += policy.normalizeTools(
                        listOf(
                            ExtensionTool(
                                extensionId = "",
                                name = name,
                                title = annotations?.optString("title")?.takeIf(String::isNotBlank) ?: name,
                                description = tool.optString("description"),
                                access = if (readOnly) ExtensionToolAccess.READ else ExtensionToolAccess.WRITE,
                                discoveredAtEpochMs = now,
                            ),
                        ),
                    ).single()
                }
                cursor = result.opt("nextCursor").let { value ->
                    when (value) {
                        null, JSONObject.NULL -> null
                        is String -> value.trim().takeIf(String::isNotEmpty)?.also {
                            require(it.length <= MAX_CURSOR_LENGTH && it.none(Char::isISOControl)) {
                                "MCP 返回了无效分页游标"
                            }
                            require(cursors.add(it)) { "MCP 返回了循环分页游标" }
                        }
                        else -> error("MCP 返回了无效分页游标")
                    }
                }
            } while (cursor != null)
            return policy.normalizeTools(discovered)
        } finally {
            val version = protocolVersion
            if (version != null) {
                sessionId[0]?.let { id -> runCatching { terminate(endpoint, bearer, id, version) } }
            }
        }
    }

    private fun terminate(
        endpoint: HttpUrl,
        bearer: ByteArray?,
        sessionId: String,
        protocolVersion: String,
    ) {
        discoveryClient.newCall(
            Request.Builder()
                .url(endpoint)
                .header("MCP-Session-Id", sessionId)
                .header("MCP-Protocol-Version", protocolVersion)
                .apply {
                    if (bearer != null) {
                        header("Authorization", "Bearer ${String(bearer, StandardCharsets.UTF_8)}")
                    }
                }
                .delete()
                .build(),
        ).also { call ->
            call.timeout().timeout(TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.execute().close()
    }

    private fun notifyInitialized(
        endpoint: HttpUrl,
        bearer: ByteArray?,
        sessionId: String?,
        protocolVersion: String,
        deadlineNanos: Long,
    ) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
            .toString()
        execute(endpoint, payload, bearer, sessionId, protocolVersion, deadlineNanos).use { response ->
            require(response.isSuccessful) { "MCP 服务返回 HTTP ${response.code}" }
        }
    }

    private fun rpc(
        endpoint: HttpUrl,
        id: Int,
        method: String,
        params: JSONObject,
        bearer: ByteArray?,
        sessionId: String?,
        protocolVersion: String? = null,
        onSession: (String) -> Unit = {},
        consumeBytes: (Long) -> Unit = {},
        deadlineNanos: Long,
    ): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        return execute(endpoint, payload, bearer, sessionId, protocolVersion, deadlineNanos).use { response ->
            require(response.isSuccessful) { "MCP 服务返回 HTTP ${response.code}" }
            response.header("MCP-Session-Id")?.takeIf(String::isNotBlank)?.let(onSession)
            val body = response.body ?: error("MCP 服务返回空响应")
            parseMcpResponse(body.source(), response.header("Content-Type"), id, consumeBytes)
        }
    }

    private fun execute(
        endpoint: HttpUrl,
        payload: String,
        bearer: ByteArray?,
        sessionId: String?,
        protocolVersion: String?,
        deadlineNanos: Long,
    ) = discoveryClient.newCall(
        Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                sessionId?.let { header("MCP-Session-Id", it) }
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                if (bearer != null) header("Authorization", "Bearer ${String(bearer, StandardCharsets.UTF_8)}")
            }
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build(),
    ).also { call ->
        require(deadlineNanos > System.nanoTime()) { "MCP 工具发现超时" }
        call.timeout().deadlineNanoTime(deadlineNanos)
    }.execute()

    companion object {
        private const val MAX_TOOLS = 256
        private const val MAX_DISCOVERY_PAGES = 32
        private const val MAX_CURSOR_LENGTH = 1_024
        private const val MAX_DISCOVERY_BODY_BYTES = 2L * 1_024 * 1_024
        private const val MAX_DISCOVERY_TOTAL_BYTES = 4L * 1_024 * 1_024
        private const val MAX_SSE_LINE_BYTES = 64L * 1_024
        private const val DISCOVERY_IDLE_TIMEOUT_SECONDS = 20L
        private const val DISCOVERY_TOTAL_TIMEOUT_SECONDS = 45L
        private const val MAX_CONFIGURED_DISCOVERY_TIMEOUT_MILLIS = 120_000L
        private const val TERMINATE_TIMEOUT_SECONDS = 5L
        private const val REQUESTED_PROTOCOL_VERSION = "2025-06-18"
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", REQUESTED_PROTOCOL_VERSION)
        private val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_.:/-]{1,160}")

        internal fun parseMcpResponse(body: String, contentType: String?, expectedId: Int): JSONObject {
            return parseMcpResponse(Buffer().writeUtf8(body), contentType, expectedId)
        }

        internal fun parseMcpResponse(
            source: BufferedSource,
            contentType: String?,
            expectedId: Int,
        ): JSONObject = parseMcpResponse(source, contentType, expectedId) {}

        private fun parseMcpResponse(
            source: BufferedSource,
            contentType: String?,
            expectedId: Int,
            consumeBytes: (Long) -> Unit,
        ): JSONObject {
            if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
                var totalBytes = 0L
                val eventData = StringBuilder()
                while (true) {
                    val line = source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                    val bytesRead = line.toByteArray(StandardCharsets.UTF_8).size + 1L
                    totalBytes += bytesRead
                    consumeBytes(bytesRead)
                    require(totalBytes <= MAX_DISCOVERY_BODY_BYTES) { "MCP 响应过大" }
                    if (line.isEmpty()) {
                        parseMatchingResponse(eventData.toString(), expectedId)?.let { return it }
                        eventData.clear()
                    } else if (line.startsWith("data:")) {
                        if (eventData.isNotEmpty()) eventData.append('\n')
                        eventData.append(line.removePrefix("data:").trimStart())
                    }
                }
            }

            val buffer = Buffer()
            var totalBytes = 0L
            while (true) {
                val read = source.read(buffer, 8_192L)
                if (read < 0) break
                totalBytes += read
                consumeBytes(read)
                require(totalBytes <= MAX_DISCOVERY_BODY_BYTES) { "MCP 响应过大" }
            }
            return parseMatchingResponse(buffer.readUtf8(), expectedId)
                ?: error("MCP 返回了无效 JSON-RPC 响应")
        }

        private fun parseMatchingResponse(value: String, expectedId: Int): JSONObject? =
            runCatching { JSONObject(value.trim()) }.getOrNull()?.takeIf {
                it.optInt("id", Int.MIN_VALUE) == expectedId
            }
    }
}
