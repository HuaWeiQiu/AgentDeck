package com.agentdeck.app.data.runtime

import android.os.Build

/**
 * Pinned Node.js (linux musl-compatible glibc builds from nodejs.org) + dsh npm package.
 * Node runs inside the existing Codex PRoot rootfs (linux userspace), not as an Android ABI binary.
 *
 * Versions locked from official sources (2026-08):
 * - Node [v24.19.0](https://nodejs.org/dist/v24.19.0/) SHASUMS256.txt
 * - npm [@deepseek-ai/dsh@0.1.0-rc.6](https://www.npmjs.com/package/@deepseek-ai/dsh)
 */
internal data class DshRuntimeTarget(
    val releaseId: String,
    val androidAbi: String,
    val nodeVersion: String,
    val dshVersion: String,
    val node: VerifiedArtifact,
)

internal object DshRuntimeManifest {
    const val SCHEMA_VERSION = 1
    const val NODE_VERSION = "v24.19.0"
    const val DSH_NPM_VERSION = "0.1.0-rc.6"
    const val DSH_NPM_SPEC = "@deepseek-ai/dsh@$DSH_NPM_VERSION"
    const val RELEASE_ID = "node-24.19.0-dsh-0.1.0-rc.6"

    /** npm package tarball is small; full tree is produced by `npm install` inside PRoot. */
    const val DSH_PACKAGE_LABEL = "DeepSeek Harness $DSH_NPM_VERSION + Node $NODE_VERSION"

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

    fun forDevice(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): DshRuntimeTarget? =
        supportedAbis.firstNotNullOfOrNull { abi -> targets.firstOrNull { it.androidAbi == abi } }

    fun estimatedDownloadBytes(target: DshRuntimeTarget = forDevice() ?: targets.first()): Long =
        target.node.sizeBytes

    private fun target(
        androidAbi: String,
        nodeArch: String,
        nodeSizeBytes: Long,
        nodeSha256: String,
    ): DshRuntimeTarget {
        val fileName = "node-$NODE_VERSION-linux-$nodeArch.tar.gz"
        val official = "https://nodejs.org/dist/$NODE_VERSION/$fileName"
        val npmmirror = "https://npmmirror.com/mirrors/node/$NODE_VERSION/$fileName"
        return DshRuntimeTarget(
            releaseId = RELEASE_ID,
            androidAbi = androidAbi,
            nodeVersion = NODE_VERSION,
            dshVersion = DSH_NPM_VERSION,
            node = VerifiedArtifact(
                fileName = fileName,
                urls = listOf(npmmirror, official),
                sizeBytes = nodeSizeBytes,
                sha256 = nodeSha256,
            ),
        )
    }
}
