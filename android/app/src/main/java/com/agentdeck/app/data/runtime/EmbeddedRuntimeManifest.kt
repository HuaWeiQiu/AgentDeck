package com.agentdeck.app.data.runtime

import android.os.Build

internal data class VerifiedArtifact(
    val fileName: String,
    /** Try in order: domestic mirrors first, official last. */
    val urls: List<String>,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(fileName.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "运行组件文件名无效" }
        require(urls.isNotEmpty()) { "运行组件至少需要一个下载地址" }
        require(urls.all { it.startsWith("https://") }) { "运行组件只能使用 HTTPS" }
        require(sizeBytes in 1..MAX_ARTIFACT_BYTES) { "运行组件大小无效" }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "运行组件校验值无效" }
    }

    /** Primary / official URL (last entry by convention). */
    val url: String get() = urls.last()

    companion object {
        private const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    }
}

internal data class EmbeddedRuntimeTarget(
    val releaseId: String,
    val ubuntuVersion: String,
    val codexVersion: String,
    val androidAbi: String,
    val codexBinaryName: String,
    val rootfs: VerifiedArtifact,
    val codex: VerifiedArtifact,
)

internal object EmbeddedRuntimeManifest {
    const val SCHEMA_VERSION = 1
    const val STABLE_RELEASE_ID = "ubuntu-24.04.4-codex-0.147.0-r2"

    private val targets = listOf(
        target(
            androidAbi = "arm64-v8a",
            ubuntuArchiveArch = "arm64",
            ubuntuSizeBytes = 29_870_567,
            ubuntuSha256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            codexTarget = "aarch64-unknown-linux-musl",
            codexSizeBytes = 91_607_658,
            codexSha256 = "eb677c80f666b1ab8b4b1d083b66e8d614b1281d960bb6f9fd8ca98f58b38b90",
        ),
        target(
            androidAbi = "x86_64",
            ubuntuArchiveArch = "amd64",
            ubuntuSizeBytes = 29_989_394,
            ubuntuSha256 = "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58",
            codexTarget = "x86_64-unknown-linux-musl",
            codexSizeBytes = 98_970_270,
            codexSha256 = "0246e2e773834e07f0fb5249ed6ebad12e4591e608f8c7bb97dd6a9690544c36",
        ),
    )

    fun forDevice(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): EmbeddedRuntimeTarget? =
        supportedAbis.firstNotNullOfOrNull { abi -> targets.firstOrNull { it.androidAbi == abi } }

    fun deviceSupported(supportedAbis: Array<String> = Build.SUPPORTED_ABIS): Boolean =
        forDevice(supportedAbis) != null

    fun supportedTargets(): List<EmbeddedRuntimeTarget> = targets.toList()

    private fun target(
        androidAbi: String,
        ubuntuArchiveArch: String,
        ubuntuSizeBytes: Long,
        ubuntuSha256: String,
        codexTarget: String,
        codexSizeBytes: Long,
        codexSha256: String,
    ): EmbeddedRuntimeTarget {
        val ubuntuVersion = "24.04.4"
        val codexVersion = "0.147.0"
        val rootfsName = "ubuntu-base-$ubuntuVersion-base-$ubuntuArchiveArch.tar.gz"
        val codexName = "codex-$codexTarget.tar.gz"
        val officialRootfs =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$rootfsName"
        val officialCodex =
            "https://github.com/openai/codex/releases/download/rust-v$codexVersion/$codexName"
        return EmbeddedRuntimeTarget(
            releaseId = STABLE_RELEASE_ID,
            ubuntuVersion = ubuntuVersion,
            codexVersion = codexVersion,
            androidAbi = androidAbi,
            codexBinaryName = "codex-$codexTarget",
            rootfs = VerifiedArtifact(
                fileName = rootfsName,
                // Domestic cdimage mirrors first (same file, same sha256), official last.
                urls = listOf(
                    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$rootfsName",
                    "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$rootfsName",
                    "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/$rootfsName",
                    officialRootfs,
                ),
                sizeBytes = ubuntuSizeBytes,
                sha256 = ubuntuSha256,
            ),
            codex = VerifiedArtifact(
                fileName = codexName,
                // GitHub is often slow in CN; try a public accelerator first, then official.
                urls = listOf(
                    "https://ghfast.top/https://github.com/openai/codex/releases/download/rust-v$codexVersion/$codexName",
                    officialCodex,
                ),
                sizeBytes = codexSizeBytes,
                sha256 = codexSha256,
            ),
        )
    }
}
