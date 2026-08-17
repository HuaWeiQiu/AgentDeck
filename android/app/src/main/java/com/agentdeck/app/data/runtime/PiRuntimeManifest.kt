package com.agentdeck.app.data.runtime

import android.os.Build

/**
 * Pinned Node.js (same train as dsh) + [pi-coding-agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent).
 *
 * Node runs inside the Codex PRoot rootfs. Package install uses `npm install --ignore-scripts`
 * (official install recommendation).
 */
internal data class PiRuntimeTarget(
    val releaseId: String,
    val androidAbi: String,
    val nodeVersion: String,
    val piVersion: String,
    val node: VerifiedArtifact,
)

internal object PiRuntimeManifest {
    const val SCHEMA_VERSION = 1
    /** Match dsh so we can reuse an already-extracted Node tree when present. */
    const val NODE_VERSION = DshRuntimeManifest.NODE_VERSION
    const val PI_NPM_VERSION = "0.84.2"
    const val PI_NPM_SPEC = "@earendil-works/pi-coding-agent@$PI_NPM_VERSION"
    const val RELEASE_ID = "node-24.19.0-pi-0.84.2"
    const val PI_PACKAGE_LABEL = "pi $PI_NPM_VERSION + Node $NODE_VERSION"

    private val targets = listOf(
        target(
            androidAbi = "arm64-v8a",
            nodeArch = "arm64",
            nodeSizeBytes = 57_128_466,
            nodeSha256 = "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f",
        ),
        target(
            androidAbi = "x86_64",
            nodeArch = "x64",
            nodeSizeBytes = 57_409_532,
            nodeSha256 = "f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4",
        ),
    )

    fun forDevice(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): PiRuntimeTarget? =
        supportedAbis.firstNotNullOfOrNull { abi -> targets.firstOrNull { it.androidAbi == abi } }

    fun estimatedDownloadBytes(target: PiRuntimeTarget = forDevice() ?: targets.first()): Long =
        target.node.sizeBytes

    private fun target(
        androidAbi: String,
        nodeArch: String,
        nodeSizeBytes: Long,
        nodeSha256: String,
    ): PiRuntimeTarget {
        val fileName = "node-$NODE_VERSION-linux-$nodeArch.tar.gz"
        val official = "https://nodejs.org/dist/$NODE_VERSION/$fileName"
        val npmmirror = "https://npmmirror.com/mirrors/node/$NODE_VERSION/$fileName"
        return PiRuntimeTarget(
            releaseId = RELEASE_ID,
            androidAbi = androidAbi,
            nodeVersion = NODE_VERSION,
            piVersion = PI_NPM_VERSION,
            node = VerifiedArtifact(
                fileName = fileName,
                urls = listOf(npmmirror, official),
                sizeBytes = nodeSizeBytes,
                sha256 = nodeSha256,
            ),
        )
    }
}
