package com.agentdeck.app.data.runtime

import android.content.Context
import java.io.File

internal class EmbeddedRuntimePaths(context: Context) {
    private val app = context.applicationContext

    val root = File(app.noBackupFilesDir, "agentdeck-runtime")
    val activeRootfs = File(root, "rootfs-${EmbeddedRuntimeManifest.RUNTIME_VERSION}")
    val stagingRootfs = File(root, ".rootfs-${EmbeddedRuntimeManifest.RUNTIME_VERSION}.staging")
    val stateDir = File(root, "state")
    val tempDir = File(root, "tmp")
    val cacheDir = File(app.cacheDir, "agentdeck-runtime-downloads")
    val marker = File(activeRootfs, ".agentdeck-runtime")
    val stagingMarker = File(stagingRootfs, ".agentdeck-runtime")

    val nativeLibraryDir: File = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot.so")
    val prootLoader = File(nativeLibraryDir, "libproot-loader.so")
    val packagedTalloc = File(nativeLibraryDir, "libtalloc.so")
    val runtimeTalloc = File(root, "libtalloc.so.2")

    fun ensureHostLayout() {
        listOf(root, stateDir, tempDir, cacheDir).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "无法创建内嵌运行环境目录"
            }
        }
        if (!runtimeTalloc.isFile || runtimeTalloc.length() != packagedTalloc.length()) {
            check(packagedTalloc.isFile) { "APK 缺少 talloc 运行组件" }
            packagedTalloc.copyTo(runtimeTalloc, overwrite = true)
        }
    }

    fun isReady(): Boolean {
        if (!marker.isFile || !File(activeRootfs, "usr/local/bin/codex").canExecute()) return false
        val values = marker.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.take(separator) to line.drop(separator + 1)
        }.toMap()
        return values["schema"] == EmbeddedRuntimeManifest.SCHEMA_VERSION.toString() &&
            values["runtime"] == EmbeddedRuntimeManifest.RUNTIME_VERSION &&
            values["abi"] == EmbeddedRuntimeManifest.ABI
    }

    fun writeStagingMarker() {
        stagingMarker.writeText(
            buildString {
                appendLine("schema=${EmbeddedRuntimeManifest.SCHEMA_VERSION}")
                appendLine("runtime=${EmbeddedRuntimeManifest.RUNTIME_VERSION}")
                appendLine("ubuntu=${EmbeddedRuntimeManifest.UBUNTU_VERSION}")
                appendLine("codex=${EmbeddedRuntimeManifest.CODEX_VERSION}")
                appendLine("abi=${EmbeddedRuntimeManifest.ABI}")
            },
        )
    }
}
