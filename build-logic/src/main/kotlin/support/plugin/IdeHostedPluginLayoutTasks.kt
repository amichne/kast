package support.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import support.tasks.CanonicalWireSchema

private val REPORT_JSON = Json { prettyPrint = true; prettyPrintIndent = "    " }

@Serializable
internal data class IdeHostCompatibilityReportDocument(
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

internal enum class IdeHostCompatibilityReportFailure {
    MALFORMED_DOCUMENT,
    SCHEMA_VERSION_MISMATCH,
    TASK_ID_MISMATCH,
    IDE_BUILD_MISMATCH,
    KOTLIN_PLUGIN_BUILD_MISMATCH,
    KAST_PLUGIN_VERSION_INVALID,
    DECLARED_KAST_PLUGIN_VERSION_INVALID,
    KAST_PLUGIN_VERSION_MISMATCH,
    RUNTIME_PROTOCOL_IDENTITY_MISMATCH,
    OPERATION_REGISTRY_DIGEST_MISMATCH,
    WIRE_SCHEMA_DIGEST_MISMATCH,
    CAPABILITY_SET_MISMATCH,
}

internal enum class IdeHostReportSchemaVersion(val value: Int) { V1(1) }
internal enum class IdeHostReportTaskId(val value: String) { HOST_COMPATIBILITY("IDE-HOST-COMPATIBILITY") }
internal enum class SupportedIdeBuild(val value: String) { IDEA_262("262.9437.185") }
internal enum class SupportedKotlinPluginBuild(val value: String) {
    IDEA_262("262.9437.185-IJ"),
}
internal enum class SupportedIdeRuntimeProtocol(val value: String) {
    V1("kast.ide-hosted.runtime.v1"),
}
internal enum class IdeHostReportCapability(val operationId: String) {
    WORKSPACE_INSPECT("workspace.inspect"),
    SYMBOL_DISCOVER("symbol.discover"),
    SYMBOL_RESOLVE("symbol.resolve"),
    SYMBOL_DESCRIBE("symbol.describe"),
}
internal enum class IdeHostReportCapabilitySet { CANONICAL }

@JvmInline
internal value class ReportedKastPluginVersion private constructor(val value: String) {
    sealed interface Refinement {
        data class Complete(val version: ReportedKastPluginVersion) : Refinement
        data object Rejected : Refinement
    }

    companion object {
        private val FORMAT = Regex(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?",
        )

        /**
         * Proof transition: report `String -> ReportedKastPluginVersion.Refinement`.
         * Establishes the closed Kast release or exact Git-describe form. Malformed versions are
         * rejected; raw text is permitted only at compatibility-report decoding.
         */
        fun refine(raw: String): Refinement = if (FORMAT.matches(raw)) {
            Refinement.Complete(ReportedKastPluginVersion(raw))
        } else {
            Refinement.Rejected
        }
    }
}

internal class AdmittedIdeHostCompatibilityReport private constructor(
    val schemaVersion: IdeHostReportSchemaVersion,
    val taskId: IdeHostReportTaskId,
    val ideBuild: SupportedIdeBuild,
    val kotlinPluginBuild: SupportedKotlinPluginBuild,
    val kastPluginVersion: ReportedKastPluginVersion,
    val runtimeProtocolIdentity: SupportedIdeRuntimeProtocol,
    val operationRegistryDigest: CompatibilityDigest,
    val wireSchemaDigest: CompatibilityDigest,
    val capabilitySet: IdeHostReportCapabilitySet,
) {
    companion object {
        /**
         * Proof transition: report JSON, physical registry bytes, and declared Kast version ->
         * `IdeHostCompatibilityReportAdmission`.
         *
         * Establishes the exact IDE-HOST-COMPATIBILITY schema, task, supported host tuple, canonical capability
         * order, physical registry digest, and sole canonical wire digest. Expected malformed or
         * mismatched evidence is finite [IdeHostCompatibilityReportFailure]. Raw values are
         * permitted only at this build-report boundary.
         */
        fun admit(
            raw: String,
            operationRegistryBytes: ByteArray,
            expectedKastPluginVersion: String,
        ): IdeHostCompatibilityReportAdmission {
            val document = try {
                REPORT_JSON.decodeFromString(IdeHostCompatibilityReportDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return reportRejected(IdeHostCompatibilityReportFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return reportRejected(IdeHostCompatibilityReportFailure.MALFORMED_DOCUMENT)
            }
            if (document.schemaVersion != IdeHostReportSchemaVersion.V1.value) {
                return reportRejected(IdeHostCompatibilityReportFailure.SCHEMA_VERSION_MISMATCH)
            }
            if (document.taskId != IdeHostReportTaskId.HOST_COMPATIBILITY.value) return reportRejected(
                IdeHostCompatibilityReportFailure.TASK_ID_MISMATCH,
            )
            if (document.ideBuild != SupportedIdeBuild.IDEA_262.value) return reportRejected(
                IdeHostCompatibilityReportFailure.IDE_BUILD_MISMATCH,
            )
            if (document.kotlinPluginBuild != SupportedKotlinPluginBuild.IDEA_262.value) {
                return reportRejected(
                    IdeHostCompatibilityReportFailure.KOTLIN_PLUGIN_BUILD_MISMATCH,
                )
            }
            val pluginVersion = when (val result = ReportedKastPluginVersion.refine(
                document.kastPluginVersion,
            )) {
                is ReportedKastPluginVersion.Refinement.Complete -> result.version
                ReportedKastPluginVersion.Refinement.Rejected -> return reportRejected(
                    IdeHostCompatibilityReportFailure.KAST_PLUGIN_VERSION_INVALID,
                )
            }
            val expectedVersion = when (val result = ReportedKastPluginVersion.refine(
                expectedKastPluginVersion,
            )) {
                is ReportedKastPluginVersion.Refinement.Complete -> result.version
                ReportedKastPluginVersion.Refinement.Rejected -> return reportRejected(
                    IdeHostCompatibilityReportFailure.DECLARED_KAST_PLUGIN_VERSION_INVALID,
                )
            }
            if (pluginVersion != expectedVersion) return reportRejected(
                IdeHostCompatibilityReportFailure.KAST_PLUGIN_VERSION_MISMATCH,
            )
            if (document.runtimeProtocolIdentity != SupportedIdeRuntimeProtocol.V1.value) {
                return reportRejected(
                    IdeHostCompatibilityReportFailure.RUNTIME_PROTOCOL_IDENTITY_MISMATCH,
                )
            }
            val registryDigest = CompatibilityDigest.observe(operationRegistryBytes)
            if (document.operationRegistryDigest != registryDigest.value) return reportRejected(
                IdeHostCompatibilityReportFailure.OPERATION_REGISTRY_DIGEST_MISMATCH,
            )
            val wireDigest = CompatibilityDigest.observe(CanonicalWireSchema.encodedBytes())
            if (document.wireSchemaDigest != wireDigest.value) return reportRejected(
                IdeHostCompatibilityReportFailure.WIRE_SCHEMA_DIGEST_MISMATCH,
            )
            if (document.capabilities != hostedCapabilities) return reportRejected(
                IdeHostCompatibilityReportFailure.CAPABILITY_SET_MISMATCH,
            )
            return IdeHostCompatibilityReportAdmission.Complete(
                AdmittedIdeHostCompatibilityReport(
                    IdeHostReportSchemaVersion.V1,
                    IdeHostReportTaskId.HOST_COMPATIBILITY,
                    SupportedIdeBuild.IDEA_262,
                    SupportedKotlinPluginBuild.IDEA_262,
                    pluginVersion,
                    SupportedIdeRuntimeProtocol.V1,
                    registryDigest,
                    wireDigest,
                    IdeHostReportCapabilitySet.CANONICAL,
                ),
            )
        }

    }
}

internal sealed interface IdeHostCompatibilityReportAdmission {
    data class Complete(val report: AdmittedIdeHostCompatibilityReport) :
        IdeHostCompatibilityReportAdmission

    data class Rejected(val failure: IdeHostCompatibilityReportFailure) :
        IdeHostCompatibilityReportAdmission
}

private val hostedCapabilities = IdeHostReportCapability.entries.map { it.operationId }

private fun reportRejected(
    failure: IdeHostCompatibilityReportFailure,
): IdeHostCompatibilityReportAdmission = IdeHostCompatibilityReportAdmission.Rejected(failure)

@CacheableTask
abstract class GenerateIdeHostCompatibilityReportTask : DefaultTask() {
    @get:Input abstract val ideBuild: Property<String>
    @get:Input abstract val kotlinPluginBuild: Property<String>
    @get:Input abstract val kastPluginVersion: Property<String>
    @get:Input abstract val runtimeProtocolIdentity: Property<String>
    @get:Input abstract val capabilities: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val operationRegistryFile: RegularFileProperty

    @get:OutputFile abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: declared pins and exact registry/wire bytes `->` IDE-HOST-COMPATIBILITY report.
     *
     * Computes both artifact digests from their canonical bytes and projects the closed report via
     * its generated serializer. Filesystem failures cross into Gradle only at this task boundary.
     */
    @TaskAction
    fun generate() {
        val registryBytes = operationRegistryFile.get().asFile.readBytes()
        val wireBytes = CanonicalWireSchema.encodedBytes()
        val report = IdeHostCompatibilityReportDocument(
            schemaVersion = 1,
            taskId = "IDE-HOST-COMPATIBILITY",
            ideBuild = ideBuild.get(),
            kotlinPluginBuild = kotlinPluginBuild.get(),
            kastPluginVersion = kastPluginVersion.get(),
            runtimeProtocolIdentity = runtimeProtocolIdentity.get(),
            operationRegistryDigest = CompatibilityDigest.observe(registryBytes).value,
            wireSchemaDigest = CompatibilityDigest.observe(wireBytes).value,
            capabilities = capabilities.get(),
        )
        writeAtomically(
            reportFile.get().asFile.toPath(),
            REPORT_JSON.encodeToString(IdeHostCompatibilityReportDocument.serializer(), report) +
                "\n",
        )
    }
}

private fun writeAtomically(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, content)
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        Files.deleteIfExists(temporary)
    }
}

@JvmInline
internal value class CompatibilityDigest private constructor(val value: String) {
    companion object {
        /** Proof transition: `ByteArray -> CompatibilityDigest` by SHA-256 observation. */
        fun observe(bytes: ByteArray): CompatibilityDigest = CompatibilityDigest(
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes),
            ),
        )
    }
}
