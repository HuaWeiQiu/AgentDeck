package com.agentdeck.app.data.runtime

import android.os.Build

internal data class VerifiedArtifact(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "运行组件文件名无效" }
        require(url.startsWith("https://")) { "运行组件只能使用 HTTPS" }
        require(sizeBytes in 1..MAX_ARTIFACT_BYTES) { "运行组件大小无效" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "运行组件校验值无效" }
    }

    companion object {
        private const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    }
}

internal object EmbeddedRuntimeManifest {
    const val SCHEMA_VERSION = 1
    const val RUNTIME_VERSION = "ubuntu-24.04.4-codex-0.147.0-r1"
    const val UBUNTU_VERSION = "24.04.4"
    const val CODEX_VERSION = "0.147.0"
    const val ABI = "arm64-v8a"

    val rootfs = VerifiedArtifact(
        fileName = "ubuntu-base-24.04.4-base-arm64.tar.gz",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-24.04.4-base-arm64.tar.gz",
        sizeBytes = 29_870_567,
        sha256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
    )

    val codex = VerifiedArtifact(
        fileName = "codex-aarch64-unknown-linux-musl.tar.gz",
        url = "https://github.com/openai/codex/releases/download/rust-v0.147.0/" +
            "codex-aarch64-unknown-linux-musl.tar.gz",
        sizeBytes = 91_607_658,
        sha256 = "eb677c80f666b1ab8b4b1d083b66e8d614b1281d960bb6f9fd8ca98f58b38b90",
    )

    fun deviceSupported(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): Boolean =
        supportedAbis.any { it == ABI }
}
