package com.agentdeck.app.domain.host

import java.security.SecureRandom

class UiAutomationSessionManager(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val selfPackage: String,
) {
    private var grant: UiAutomationGrant? = null
    private var currentSnapshot: UiSnapshot? = null
    private var lastFingerprint: String? = null
    private var sameFingerprintCount: Int = 0

    @Synchronized
    fun start(
        conversationId: String,
        instanceId: String,
        allowedPackages: Set<String>,
        steps: Int = UiAutomationLimits.DEFAULT_STEPS,
        ttlMs: Long = UiAutomationLimits.DEFAULT_TTL_MS,
    ): UiAutomationGrant {
        val cleaned = allowedPackages
            .map { it.trim() }
            .filter { it.isNotBlank() && !SensitiveUiClassifier.isDeniedPackage(it, selfPackage) }
            .toSet()
        require(cleaned.isNotEmpty()) { "没有可操作的应用" }
        grant = UiAutomationGrant(
            conversationId = conversationId,
            instanceId = instanceId,
            allowedPackages = cleaned,
            expiresAtEpochMs = nowMs() + ttlMs,
            remainingSteps = steps.coerceIn(UiAutomationLimits.MIN_STEPS, UiAutomationLimits.MAX_STEPS),
            sessionNonce = randomNonce(),
        )
        currentSnapshot = null
        lastFingerprint = null
        sameFingerprintCount = 0
        return grant!!
    }

    @Synchronized
    fun stop() {
        grant = null
        currentSnapshot = null
        lastFingerprint = null
        sameFingerprintCount = 0
    }

    @Synchronized
    fun current(): UiAutomationGrant? = grant?.takeIf { nowMs() < it.expiresAtEpochMs }

    @Synchronized
    fun rememberSnapshot(snapshot: UiSnapshot) {
        currentSnapshot = snapshot
        val fingerprint = snapshot.packageName + ":" + snapshot.nodes.joinToString { it.text + it.role }
        if (fingerprint == lastFingerprint) {
            sameFingerprintCount += 1
        } else {
            lastFingerprint = fingerprint
            sameFingerprintCount = 1
        }
    }

    @Synchronized
    fun noProgress(): Boolean = sameFingerprintCount >= 3

    @Synchronized
    fun consumeStep(): HostToolResult.Denied? {
        val active = current() ?: return HostToolResult.Denied("UI_SESSION_INACTIVE", "请先开始一次屏幕任务")
        if (active.remainingSteps <= 0) {
            stop()
            return HostToolResult.Denied("UI_SESSION_BUDGET", "这次屏幕任务的步数已经用完")
        }
        grant = active.copy(remainingSteps = active.remainingSteps - 1)
        currentSnapshot = null
        return null
    }

    @Synchronized
    fun requireSnapshotNode(snapshotId: String, nodeId: String): Pair<UiSnapshot, UiSnapshotNode>? {
        val snapshot = currentSnapshot ?: return null
        if (snapshot.snapshotId != snapshotId) return null
        val node = snapshot.nodes.firstOrNull { it.nodeId == nodeId } ?: return null
        return snapshot to node
    }

    @Synchronized
    fun packageAllowed(packageName: String): Boolean {
        val active = current() ?: return false
        if (SensitiveUiClassifier.isDeniedPackage(packageName, selfPackage)) return false
        return packageName in active.allowedPackages
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
