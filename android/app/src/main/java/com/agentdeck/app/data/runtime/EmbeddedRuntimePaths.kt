package com.agentdeck.app.data.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class EmbeddedRuntimePaths(context: Context) {
    private val app = context.applicationContext

    val root = File(app.noBackupFilesDir, "agentdeck-runtime")
    val activeRootfs = File(root, "rootfs-${EmbeddedRuntimeManifest.RUNTIME_VERSION}")
    val stagingRootfs = File(root, ".rootfs-${EmbeddedRuntimeManifest.RUNTIME_VERSION}.staging")
    val stateDir = File(root, "state")
    val tempDir = File(root, "tmp")
    val codexHome = File(root, "codex-home")
    val projectsHome = File(root, "projects")
    val cacheDir = File(app.cacheDir, "agentdeck-runtime-downloads")
    val marker = File(activeRootfs, ".agentdeck-runtime")
    val stagingMarker = File(stagingRootfs, ".agentdeck-runtime")

    val nativeLibraryDir: File = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot.so")
    val prootLoader = File(nativeLibraryDir, "libproot-loader.so")
    val packagedTalloc = File(nativeLibraryDir, "libtalloc.so")
    val runtimeTalloc = File(root, "libtalloc.so.2")

    fun ensureHostLayout() = synchronized(HOST_LAYOUT_LOCK) {
        listOf(root, stateDir, tempDir, codexHome, projectsHome, cacheDir).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "无法创建内嵌运行环境目录"
            }
        }
        migrateLegacyDirectory(
            source = File(activeRootfs, "root/.codex"),
            destination = codexHome,
            marker = File(codexHome, ".agentdeck-migrated-v1"),
        )
        migrateLegacyDirectory(
            source = File(activeRootfs, "root/projects"),
            destination = projectsHome,
            marker = File(root, ".agentdeck-projects-migrated-v1"),
        )
        if (activeRootfs.isDirectory) {
            listOf("root/.codex", "root/projects").forEach { relative ->
                val guestDirectory = File(activeRootfs, relative)
                check(guestDirectory.mkdirs() || guestDirectory.isDirectory) {
                    "无法准备内嵌持久目录"
                }
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

    private fun migrateLegacyDirectory(
        source: File,
        destination: File,
        marker: File,
    ) {
        if (marker.isFile || !source.isDirectory) return
        val sourceRoot = source.toPath()
        val destinationRoot = destination.toPath()
        Files.walkFileTree(
            sourceRoot,
            object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    directory: java.nio.file.Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                    val relative = sourceRoot.relativize(directory)
                    Files.createDirectories(destinationRoot.resolve(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: java.nio.file.Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                        return FileVisitResult.CONTINUE
                    }
                    val destination = destinationRoot.resolve(sourceRoot.relativize(file)).normalize()
                    check(destination.startsWith(destinationRoot)) { "运行数据迁移路径无效" }
                    if (!Files.exists(destination)) {
                        destination.parent?.let(Files::createDirectories)
                        Files.copy(file, destination)
                        Os.chmod(
                            destination.toString(),
                            Os.stat(file.toString()).st_mode and FILE_MODE_MASK,
                        )
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        marker.writeText("1\n")
    }

    companion object {
        private const val FILE_MODE_MASK = 0b111111111
        private val HOST_LAYOUT_LOCK = Any()
    }
}
