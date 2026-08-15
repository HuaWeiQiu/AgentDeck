package com.agentdeck.app.data.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal class EmbeddedRuntimePaths(
    context: Context,
    internal val runtimeTarget: EmbeddedRuntimeTarget? = EmbeddedRuntimeManifest.forDevice(),
) {
    private val app = context.applicationContext
    private val releaseId = runtimeTarget?.releaseId ?: "unsupported"

    val root = File(app.noBackupFilesDir, "agentdeck-runtime")
    val activeRootfs = File(root, "rootfs-$releaseId")
    val stagingRootfs = File(root, ".rootfs-$releaseId.staging")
    val stateDir = File(root, "state")
    val tempDir = File(root, "tmp")
    val codexHome = File(root, "codex-home")
    val projectsHome = File(root, "projects")
    val extensionPackages = File(root, "extensions/packages")
    val extensionSessionSnapshots = File(root, "extensions/sessions")
    val cacheDir = File(app.cacheDir, "agentdeck-runtime-downloads")
    val marker = File(activeRootfs, ".agentdeck-runtime")
    val stagingMarker = File(stagingRootfs, ".agentdeck-runtime")

    val nativeLibraryDir: File = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot.so")
    val prootLoader = File(nativeLibraryDir, "libproot-loader.so")
    val packagedTalloc = File(nativeLibraryDir, "libtalloc.so")
    val runtimeTalloc = File(root, "libtalloc.so.2")

    fun ensureHostLayout() = synchronized(HOST_LAYOUT_LOCK) {
        listOf(
            root,
            stateDir,
            tempDir,
            codexHome,
            projectsHome,
            extensionPackages,
            extensionSessionSnapshots,
            cacheDir,
        ).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "无法创建内嵌运行环境目录"
            }
        }
        // Beta builds briefly stored Skill snapshots under stateDir, which is bound
        // wholesale into every PRoot. New snapshots never use this shared location.
        stateDir.listFiles { file -> file.name.matches(Regex("skills\\.[a-f0-9]{1,16}")) }
            .orEmpty()
            .forEach { stale -> deleteTreeWithoutFollowingLinks(stale.toPath()) }
        if (!sessionSnapshotsReconciled) {
            extensionSessionSnapshots.listFiles { file ->
                file.name.matches(Regex("skills\\.[a-f0-9]{1,16}"))
            }.orEmpty().forEach { stale -> deleteTreeWithoutFollowingLinks(stale.toPath()) }
            sessionSnapshotsReconciled = true
        }
        val runtimeRoots = versionedRuntimeRoots()
        migrateLegacyDirectories(
            sources = runtimeRoots.map { File(it, "root/.codex") },
            destination = codexHome,
            marker = File(codexHome, ".agentdeck-migrated-v1"),
        )
        migrateLegacyDirectories(
            sources = runtimeRoots.map { File(it, "root/projects") },
            destination = projectsHome,
            marker = File(root, ".agentdeck-projects-migrated-v1"),
        )
        if (activeRootfs.isDirectory) {
            listOf("root/.codex", "root/.codex/skills", "root/.agents/skills", "root/projects").forEach { relative ->
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
        val target = runtimeTarget ?: return false
        if (!marker.isFile || !File(activeRootfs, "usr/local/bin/codex").canExecute()) return false
        return runtimeMarkerMatches(marker.readText(), target)
    }

    fun writeStagingMarker() {
        val target = requireNotNull(runtimeTarget) { "当前设备架构没有 Runtime 清单" }
        stagingMarker.writeText(runtimeMarkerContent(target))
    }

    fun usedBytes(): Long = synchronized(HOST_LAYOUT_LOCK) {
        sequenceOf(activeRootfs, stagingRootfs, cacheDir)
            .filter { it.exists() }
            .sumOf(::directorySize)
    }

    fun removeCodexRuntime() = synchronized(HOST_LAYOUT_LOCK) {
        sequenceOf(activeRootfs, stagingRootfs).forEach { directory ->
            if (directory.exists()) deleteTreeWithoutFollowingLinks(directory.toPath())
        }
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete() else deleteTreeWithoutFollowingLinks(file.toPath())
        }
    }

    fun removeObsoleteRuntimeRoots() = synchronized(HOST_LAYOUT_LOCK) {
        check(isReady()) { "新运行环境尚未验证，不能清理旧版本" }
        versionedRuntimeRoots()
            .filterNot { it.absolutePath == activeRootfs.absolutePath }
            .forEach { directory -> deleteTreeWithoutFollowingLinks(directory.toPath()) }
    }

    private fun migrateLegacyDirectories(
        sources: List<File>,
        destination: File,
        marker: File,
    ) {
        if (marker.isFile) return
        val destinationRoot = destination.toPath()
        var migratedSource = false
        sources.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
            migratedSource = true
            val sourceRoot = source.toPath()
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
                        val target = destinationRoot.resolve(sourceRoot.relativize(file)).normalize()
                        check(target.startsWith(destinationRoot)) { "运行数据迁移路径无效" }
                        if (!Files.exists(target)) {
                            target.parent?.let(Files::createDirectories)
                            Files.copy(file, target)
                            Os.chmod(
                                target.toString(),
                                Os.stat(file.toString()).st_mode and FILE_MODE_MASK,
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        if (migratedSource) marker.writeText("1\n")
    }

    private fun versionedRuntimeRoots(): List<File> {
        val current = activeRootfs.takeIf(::isVersionedRuntimeRoot)
        val others = root.listFiles().orEmpty()
            .asSequence()
            .filter(::isVersionedRuntimeRoot)
            .filterNot { it.absolutePath == activeRootfs.absolutePath }
            .sortedByDescending(File::lastModified)
            .toList()
        return listOfNotNull(current) + others
    }

    private fun isVersionedRuntimeRoot(candidate: File): Boolean {
        if (candidate.parentFile?.absolutePath != root.absolutePath) return false
        if (!candidate.name.matches(Regex("rootfs-[A-Za-z0-9._-]{1,160}"))) return false
        if (!Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return Files.isRegularFile(
            File(candidate, ".agentdeck-runtime").toPath(),
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    companion object {
        private const val FILE_MODE_MASK = 0b111111111
        private val HOST_LAYOUT_LOCK = Any()
        private var sessionSnapshotsReconciled = false
    }
}

internal fun directorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length()
    return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

internal fun deleteTreeWithoutFollowingLinks(root: java.nio.file.Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(
                file: java.nio.file.Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: java.nio.file.Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

internal fun runtimeMarkerContent(target: EmbeddedRuntimeTarget): String = buildString {
    appendLine("schema=${EmbeddedRuntimeManifest.SCHEMA_VERSION}")
    appendLine("runtime=${target.releaseId}")
    appendLine("ubuntu=${target.ubuntuVersion}")
    appendLine("codex=${target.codexVersion}")
    appendLine("abi=${target.androidAbi}")
}

internal fun runtimeMarkerMatches(content: String, target: EmbeddedRuntimeTarget): Boolean {
    val values = content.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.take(separator) to line.drop(separator + 1)
    }.toMap()
    return values["schema"] == EmbeddedRuntimeManifest.SCHEMA_VERSION.toString() &&
        values["runtime"] == target.releaseId &&
        values["ubuntu"] == target.ubuntuVersion &&
        values["codex"] == target.codexVersion &&
        values["abi"] == target.androidAbi
}
