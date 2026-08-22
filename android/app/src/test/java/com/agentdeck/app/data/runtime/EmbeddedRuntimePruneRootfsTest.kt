package com.agentdeck.app.data.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EmbeddedRuntimePruneRootfsTest {
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        rootfs = Files.createTempDirectory("agentdeck-prune-test").toFile()
    }

    @After
    fun tearDown() {
        rootfs.deleteRecursively()
    }

    private fun file(relative: String, size: Int): File =
        File(rootfs, relative).apply {
            parentFile?.mkdirs()
            writeText("x".repeat(size))
        }

    @Test
    fun `removes deb cache apt lists doc and man`() {
        file("var/cache/apt/archives/git_1.0.deb", 300)
        file("var/cache/apt/archives/partial/tmp.part", 10)
        file("var/lib/apt/lists/archive.ubuntu.com_noble_InRelease", 200)
        file("var/lib/apt/lists/partial/lock", 10)
        file("usr/share/doc/git/copyright", 100)
        file("usr/share/man/man1/git.1.gz", 150)
        file("usr/share/locale/zh_CN/LC_MESSAGES/git.mo", 500)
        file("root/projects/default/keep.txt", 20)
        file("root/.codex/config.toml", 20)
        file("etc/os-release", 30)

        val freed = pruneRootfsForSize(rootfs)

        assertEquals(300L + 200L + 100L + 150L, freed)
        // apt 缓存 .deb 已删，partial 保留
        assertFalse(File(rootfs, "var/cache/apt/archives/git_1.0.deb").exists())
        assertTrue(File(rootfs, "var/cache/apt/archives/partial").isDirectory)
        assertTrue(File(rootfs, "var/cache/apt/archives/partial/tmp.part").exists())
        // apt 索引已删，partial 保留
        assertFalse(File(rootfs, "var/lib/apt/lists/archive.ubuntu.com_noble_InRelease").exists())
        assertTrue(File(rootfs, "var/lib/apt/lists/partial").isDirectory)
        assertTrue(File(rootfs, "var/lib/apt/lists/partial/lock").exists())
        // doc/man 内容已删，目录本身保留
        assertTrue(File(rootfs, "usr/share/doc").isDirectory)
        assertTrue(File(rootfs, "usr/share/man").isDirectory)
        assertFalse(File(rootfs, "usr/share/doc/git").exists())
        assertFalse(File(rootfs, "usr/share/man/man1").exists())
        // locale 不动
        assertTrue(File(rootfs, "usr/share/locale/zh_CN/LC_MESSAGES/git.mo").exists())
        // 绑定目录与系统文件不动
        assertTrue(File(rootfs, "root/projects/default/keep.txt").exists())
        assertTrue(File(rootfs, "root/.codex/config.toml").exists())
        assertTrue(File(rootfs, "etc/os-release").exists())
    }

    @Test
    fun `missing directories are tolerated`() {
        file("etc/os-release", 30)

        val freed = pruneRootfsForSize(rootfs)

        assertEquals(0L, freed)
        assertTrue(rootfs.isDirectory)
    }

    @Test
    fun `logs each pruned entry`() {
        file("var/lib/apt/lists/noble_InRelease", 100)
        val logs = mutableListOf<String>()

        pruneRootfsForSize(rootfs) { logs += it }

        assertTrue(logs.any { it.contains("apt 索引 noble_InRelease") })
    }
}
