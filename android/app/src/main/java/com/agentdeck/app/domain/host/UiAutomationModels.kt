package com.agentdeck.app.domain.host

data class UiSnapshotNode(
    val nodeId: String,
    val parentId: String?,
    val role: String,
    val text: String,
    val resourceId: String,
    val bounds: List<Float>,
    val states: List<String>,
    val actions: List<String>,
)

data class UiSnapshot(
    val schemaVersion: Int = 2,
    val snapshotId: String,
    val packageName: String,
    val windowTitle: String,
    val capturedAtEpochMs: Long,
    val truncated: Boolean,
    val sensitive: Boolean = false,
    val nodes: List<UiSnapshotNode>,
)

data class RawUiNode(
    val fingerprint: String,
    val parentFingerprint: String? = null,
    val packageName: String,
    val className: String,
    val text: String = "",
    val contentDescription: String = "",
    val resourceId: String = "",
    val password: Boolean = false,
    val inputType: Int = 0,
    val autofillHint: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val bounds: IntArray = intArrayOf(0, 0, 0, 0),
    val windowWidth: Int = 1,
    val windowHeight: Int = 1,
)

data class UiAutomationGrant(
    val conversationId: String,
    val instanceId: String,
    val allowedPackages: Set<String>,
    val expiresAtEpochMs: Long,
    val remainingSteps: Int,
    val sessionNonce: String,
)

object UiAutomationLimits {
    const val MAX_NODES = 300
    const val MAX_DEPTH = 24
    const val MAX_FIELD_CHARS = 256
    const val MAX_JSON_CHARS = 64 * 1024
    const val DEFAULT_STEPS = 20
    const val MIN_STEPS = 5
    const val MAX_STEPS = 50
    const val DEFAULT_TTL_MS = 3L * 60 * 1000
}
