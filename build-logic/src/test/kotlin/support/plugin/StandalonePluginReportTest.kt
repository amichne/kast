package support.plugin

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StandalonePluginReportTest {
    @field:TempDir
    lateinit var root: Path

    @Test
    fun `closed report refines and binds the physical plugin archive`() {
        val fixture = fixture()
        val decoded = assertInstanceOf(
            StandalonePluginReportResult.Complete::class.java,
            decodeStandalonePluginReport(fixture.report),
        ).report

        val verified = assertInstanceOf(
            StandalonePluginArchiveResult.Complete::class.java,
            verifyStandalonePluginArchive(root, decoded),
        ).report

        assertEquals(KastStandalonePlugin.id, verified.report.pluginId)
        assertEquals(1, verified.report.payloadJars.size)
        assertEquals(fixture.archivePath, verified.report.artifact.path.value)
    }

    @Test
    fun `closed report rejects unknown fields and invalid payload identity`() {
        val fixture = fixture()
        assertEquals(
            StandalonePluginReportResult.Rejected(
                StandalonePluginReportFailure.MALFORMED_DOCUMENT,
            ),
            decodeStandalonePluginReport(fixture.report.replaceFirst("{", "{\"unknown\":1,")),
        )
        assertEquals(
            StandalonePluginReportResult.Rejected(
                StandalonePluginReportFailure.PAYLOAD_ENTRY_INVALID,
            ),
            decodeStandalonePluginReport(
                fixture.report.replace("kast-indexer/lib/plugin.jar", "idea-home/lib/plugin.jar"),
            ),
        )
    }

    @Test
    fun `archive verification rejects bytes substituted after report generation`() {
        val fixture = fixture()
        val decoded = assertInstanceOf(
            StandalonePluginReportResult.Complete::class.java,
            decodeStandalonePluginReport(fixture.report),
        ).report
        val archive = root.resolve(fixture.archivePath)
        val substituted = Files.readAllBytes(archive).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        Files.write(archive, substituted)

        assertEquals(
            StandalonePluginArchiveResult.Rejected(
                StandalonePluginReportFailure.ARTIFACT_DIGEST_MISMATCH,
            ),
            verifyStandalonePluginArchive(root, decoded),
        )
    }

    private fun fixture(): ReportFixture {
        val jarBytes = descriptorJar()
        val entry = "kast-indexer/lib/plugin.jar"
        val archiveBytes = zip(entry, jarBytes)
        val archivePath = "ide-plugin/build/distributions/kast-ide-plugin-test.zip"
        val archive = root.resolve(archivePath)
        Files.createDirectories(archive.parent)
        Files.write(archive, archiveBytes)
        val report = encodeStandalonePluginReport(
            StandalonePluginReportDocument(
                1,
                "KVP-010",
                KastStandalonePlugin.id.value,
                entry,
                PluginArtifactDocument(
                    archivePath,
                    StandalonePluginDigest.observe(archiveBytes).value,
                    archiveBytes.size.toLong(),
                ),
                listOf(
                    PayloadJarDocument(
                        entry,
                        StandalonePluginDigest.observe(jarBytes).value,
                        jarBytes.size.toLong(),
                    ),
                ),
            ),
        )
        return ReportFixture(archivePath, report)
    }

    private fun descriptorJar(): ByteArray = ByteArrayOutputStream().use { buffer ->
        JarOutputStream(buffer).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/plugin.xml"))
            val rootElement = "idea-" + "plugin"
            jar.write(
                ("<$rootElement><id>${KastStandalonePlugin.id.value}</id>" +
                    "<extensions><appStarter/><projectResolve/></extensions></$rootElement>")
                    .toByteArray(),
            )
            jar.closeEntry()
        }
        buffer.toByteArray()
    }

    private fun zip(entry: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        }
        buffer.toByteArray()
    }

    private data class ReportFixture(val archivePath: String, val report: String)
}
