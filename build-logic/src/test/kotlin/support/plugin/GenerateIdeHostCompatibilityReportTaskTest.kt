package support.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.CanonicalWireSchema

class GenerateIdeHostCompatibilityReportTaskTest {
    @field:TempDir
    lateinit var root: Path

    @Test
    fun `report digests bind physical registry bytes and the sole canonical wire schema`() {
        val registry = root.resolve("operation-registry.json")
        Files.writeString(registry, "registry-one")
        val task = ProjectBuilder.builder().withProjectDir(root.toFile()).build().tasks.register(
            "generateIdeHostCompatibilityReportUnderTest",
            GenerateIdeHostCompatibilityReportTask::class.java,
        ).get().apply {
            ideBuild.set("262.9437.185")
            kotlinPluginBuild.set("262.9437.185-IJ")
            kastPluginVersion.set("0.28.1")
            runtimeProtocolIdentity.set("kast.ide-hosted.runtime.v1")
            capabilities.set(HOSTED_CAPABILITIES)
            operationRegistryFile.set(registry.toFile())
            reportFile.set(root.resolve("report.json").toFile())
        }

        task.generate()
        val first = decodeReport(task)
        Files.writeString(registry, "registry-two")
        task.generate()
        val second = decodeReport(task)

        assertNotEquals(first.operationRegistryDigest, second.operationRegistryDigest)
        assertEquals(digest("registry-one".toByteArray()), first.operationRegistryDigest)
        assertEquals(digest("registry-two".toByteArray()), second.operationRegistryDigest)
        assertEquals(digest(CanonicalWireSchema.encodedBytes()), first.wireSchemaDigest)
        assertEquals(first.wireSchemaDigest, second.wireSchemaDigest)
    }

    private fun decodeReport(task: GenerateIdeHostCompatibilityReportTask): ReportDocument =
        REPORT_JSON.decodeFromString(
            ReportDocument.serializer(),
            task.reportFile.get().asFile.readText(),
        )

    private fun digest(bytes: ByteArray): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )

    private companion object {
        val REPORT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
        val HOSTED_CAPABILITIES = listOf(
            "workspace.inspect",
            "symbol.discover",
            "symbol.resolve",
            "symbol.describe",
        )
    }
}

@Serializable
private data class ReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val capabilities: List<String>,
)
