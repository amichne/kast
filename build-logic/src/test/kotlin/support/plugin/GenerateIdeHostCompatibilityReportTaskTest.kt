package support.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.fail
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

        val admitted = when (val result = AdmittedIdeHostCompatibilityReport.admit(
            task.reportFile.get().asFile.readText(),
            "registry-two".toByteArray(),
            "0.28.1",
        )) {
            is IdeHostCompatibilityReportAdmission.Complete -> result.report
            is IdeHostCompatibilityReportAdmission.Rejected ->
                fail("generated report rejected: ${result.failure}")
        }
        assertEquals("0.28.1", admitted.kastPluginVersion.value)
        assertEquals(
            IdeHostReportCapabilitySet.CANONICAL,
            admitted.capabilitySet,
        )
        assertEquals(
            IdeHostCompatibilityReportAdmission.Rejected(
                IdeHostCompatibilityReportFailure.OPERATION_REGISTRY_DIGEST_MISMATCH,
            ),
            AdmittedIdeHostCompatibilityReport.admit(
                task.reportFile.get().asFile.readText(),
                "registry-three".toByteArray(),
                "0.28.1",
            ),
        )
    }

    @Test
    fun `metadata admission rejects every compatibility report mismatch as finite data`() {
        val registryBytes = "registry".toByteArray()
        val exact = IdeHostCompatibilityReportDocument(
            schemaVersion = 1,
            taskId = "IDE-HOST-COMPATIBILITY",
            ideBuild = "262.9437.185",
            kotlinPluginBuild = "262.9437.185-IJ",
            kastPluginVersion = "0.28.1-31-g25995e3fd",
            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
            operationRegistryDigest = digest(registryBytes),
            wireSchemaDigest = digest(CanonicalWireSchema.encodedBytes()),
            capabilities = HOSTED_CAPABILITIES,
        )
        val cases = listOf(
            exact.copy(schemaVersion = 2) to
                IdeHostCompatibilityReportFailure.SCHEMA_VERSION_MISMATCH,
            exact.copy(taskId = "WRONG-TASK") to
                IdeHostCompatibilityReportFailure.TASK_ID_MISMATCH,
            exact.copy(ideBuild = "262.9437.186") to
                IdeHostCompatibilityReportFailure.IDE_BUILD_MISMATCH,
            exact.copy(kotlinPluginBuild = "262.9437.186-IJ") to
                IdeHostCompatibilityReportFailure.KOTLIN_PLUGIN_BUILD_MISMATCH,
            exact.copy(kastPluginVersion = "dev") to
                IdeHostCompatibilityReportFailure.KAST_PLUGIN_VERSION_INVALID,
            exact.copy(kastPluginVersion = "9.9.9") to
                IdeHostCompatibilityReportFailure.KAST_PLUGIN_VERSION_MISMATCH,
            exact.copy(runtimeProtocolIdentity = "kast.ide-hosted.runtime.v2") to
                IdeHostCompatibilityReportFailure.RUNTIME_PROTOCOL_IDENTITY_MISMATCH,
            exact.copy(operationRegistryDigest = digest("other".toByteArray())) to
                IdeHostCompatibilityReportFailure.OPERATION_REGISTRY_DIGEST_MISMATCH,
            exact.copy(wireSchemaDigest = digest("other".toByteArray())) to
                IdeHostCompatibilityReportFailure.WIRE_SCHEMA_DIGEST_MISMATCH,
            exact.copy(capabilities = HOSTED_CAPABILITIES.reversed()) to
                IdeHostCompatibilityReportFailure.CAPABILITY_SET_MISMATCH,
        )

        assertEquals(
            IdeHostCompatibilityReportAdmission.Rejected(
                IdeHostCompatibilityReportFailure.MALFORMED_DOCUMENT,
            ),
            AdmittedIdeHostCompatibilityReport.admit(
                "{",
                registryBytes,
                exact.kastPluginVersion,
            ),
        )
        cases.forEach { (document, failure) ->
            assertEquals(
                IdeHostCompatibilityReportAdmission.Rejected(failure),
                AdmittedIdeHostCompatibilityReport.admit(
                    REPORT_JSON.encodeToString(
                        IdeHostCompatibilityReportDocument.serializer(),
                        document,
                    ),
                    registryBytes,
                    exact.kastPluginVersion,
                ),
            )
        }
        assertEquals(
            IdeHostCompatibilityReportAdmission.Rejected(
                IdeHostCompatibilityReportFailure.DECLARED_KAST_PLUGIN_VERSION_INVALID,
            ),
            AdmittedIdeHostCompatibilityReport.admit(
                REPORT_JSON.encodeToString(
                    IdeHostCompatibilityReportDocument.serializer(),
                    exact,
                ),
                registryBytes,
                "dev",
            ),
        )
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
