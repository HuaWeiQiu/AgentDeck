package com.agentdeck.app.data.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class EmbeddedRuntimeDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        cacheDir = Files.createTempDirectory("agentdeck-download-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `fresh download writes verified target and reports byte progress`() = runBlocking {
        val content = randomBytes(1_000)
        val artifact = artifact(content)
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val progress = mutableListOf<Long>()

        val file = downloadArtifact(cacheDir, artifact, client) { progress += it }

        assertEquals(File(cacheDir, artifact.fileName).absolutePath, file.absolutePath)
        assertArrayEquals(content, file.readBytes())
        assertFalse(File(cacheDir, ".${artifact.fileName}.part").exists())
        assertEquals(artifact.sizeBytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `cached verified target skips network entirely`() = runBlocking {
        val content = randomBytes(512)
        val artifact = artifact(content)
        File(cacheDir, artifact.fileName).writeBytes(content)
        val progress = mutableListOf<Long>()

        val file = downloadArtifact(cacheDir, artifact, client) { progress += it }

        assertArrayEquals(content, file.readBytes())
        assertEquals(listOf(artifact.sizeBytes), progress)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `resumes partial download with range request`() = runBlocking {
        val content = randomBytes(1_000)
        val artifact = artifact(content)
        val head = content.copyOfRange(0, 400)
        val tail = content.copyOfRange(400, content.size)
        File(cacheDir, ".${artifact.fileName}.part").writeBytes(head)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 400-999/1000")
                .setBody(Buffer().write(tail)),
        )

        val file = downloadArtifact(cacheDir, artifact, client)

        assertArrayEquals(content, file.readBytes())
        val request = server.takeRequest()
        assertEquals("bytes=400-", request.getHeader("Range"))
    }

    @Test
    fun `falls back to full download when server ignores range`() = runBlocking {
        val content = randomBytes(1_000)
        val artifact = artifact(content)
        File(cacheDir, ".${artifact.fileName}.part").writeBytes(randomBytes(300))
        server.enqueue(MockResponse().setBody(Buffer().write(content)))

        val file = downloadArtifact(cacheDir, artifact, client)

        assertArrayEquals(content, file.readBytes())
        val request = server.takeRequest()
        assertEquals("bytes=300-", request.getHeader("Range"))
    }

    @Test
    fun `retries network failures and still verifies checksum`() = runBlocking {
        val content = randomBytes(800)
        val artifact = artifact(content)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody(Buffer().write(content)))

        val file = downloadArtifact(cacheDir, artifact, client)

        assertArrayEquals(content, file.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `checksum failure is not retried`() = runBlocking {
        val artifact = artifact(randomBytes(600))
        server.enqueue(MockResponse().setBody(Buffer().write(randomBytes(600))))

        try {
            downloadArtifact(cacheDir, artifact, client)
            fail("校验失败应抛错")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("SHA-256"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `http error is not retried`() = runBlocking {
        val artifact = artifact(randomBytes(600))
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            downloadArtifact(cacheDir, artifact, client)
            fail("HTTP 错误应抛错")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("500"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `falls back to next mirror url after first source fails`() = runBlocking {
        val content = randomBytes(700)
        val sha = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { byte -> "%02x".format(byte) }
        val bad = server.url("/bad").toString()
        val good = server.url("/good").toString()
        val artifact = VerifiedArtifact(
            fileName = "mirror-${content.size}.bin",
            urls = listOf(bad, good),
            sizeBytes = content.size.toLong(),
            sha256 = sha,
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(content)))

        val file = downloadArtifact(cacheDir, artifact, client)

        assertArrayEquals(content, file.readBytes())
        assertEquals(2, server.requestCount)
        assertEquals("/bad", server.takeRequest().path)
        assertEquals("/good", server.takeRequest().path)
    }

    private fun artifact(content: ByteArray): VerifiedArtifact {
        val sha = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { byte -> "%02x".format(byte) }
        return VerifiedArtifact(
            fileName = "artifact-${content.size}.bin",
            urls = listOf(server.url("/download/${content.size}").toString()),
            sizeBytes = content.size.toLong(),
            sha256 = sha,
        )
    }

    private val random = java.util.Random(42)

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(random::nextBytes)
}
