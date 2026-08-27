package support.tasks

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@Serializable
private data class ArchitectureGateDocument(
    val schemaVersion: Int,
    val status: String,
    val findings: List<ArchitectureFindingDocument>,
)

@Serializable
private data class ArchitectureFindingDocument(val code: String, val message: String)

@Serializable
private data class FirewallReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val role: String,
    val stage: String,
    val modulePolicies: List<FirewallModuleDocument>,
    val forbiddenAuthorities: List<FirewallAuthorityDocument>,
)

@Serializable
private data class FirewallModuleDocument(
    val module: String,
    val lifecycle: String,
    val allowedDependencies: List<String>,
    val allowedEffects: List<String>,
)

@Serializable
private data class FirewallAuthorityDocument(
    val authority: String,
    val effects: List<String>,
)

@Serializable
private data class PluginLayoutReportDocument(
    val schemaVersion: Int,
    val taskId: String,
    val policyId: String,
    val pluginSizeCeilingBytes: Long,
    val artifact: PluginArtifactDocument,
    val scannedClassCount: Int,
    val violationCount: Int,
    val nestedJars: List<PluginJarDocument>,
)

@Serializable
private data class PluginArtifactDocument(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
private data class PluginJarDocument(
    val entry: String,
    val sha256: String,
    val sizeBytes: Long,
    val classCount: Int,
    val classOwnerDigest: String,
)

@Serializable
private enum class StaticProofOutcome { COMPLETE, QUALIFIED, REJECTED }

@Serializable
private data class StaticProofEvidenceDocument(
    val authority: String,
    val sha256: String,
    val observationCount: Int,
    val outcome: StaticProofOutcome,
)

@Serializable
private data class VfsPassiveStaticProofDocument(
    val schemaVersion: Int,
    val taskId: String,
    val publicInterface: String,
    val outcome: StaticProofOutcome,
    val hostedModuleCount: Int,
    val forbiddenAuthorityCount: Int,
    val sourceFileCount: Int,
    val sourceSetSha256: String,
    val scannedClassCount: Int,
    val transitiveArtifactCount: Int,
    val violationCount: Int,
    val evidence: List<StaticProofEvidenceDocument>,
)

private enum class StaticProofFailure {
    INPUT_UNREADABLE,
    MALFORMED_DOCUMENT,
    ARCHITECTURE_GATE_REJECTED,
    FIREWALL_REJECTED,
    PLUGIN_LAYOUT_REJECTED,
    REPORT_WRITE_REJECTED,
}

private sealed interface StaticProofAdmission {
    data class Complete(val proof: VfsPassiveStaticProofDocument) : StaticProofAdmission
    data class Qualified(val failure: StaticProofFailure) : StaticProofAdmission
    data class Rejected(val failure: StaticProofFailure) : StaticProofAdmission
}

private sealed interface StaticDocumentDecode<out T> {
    data class Complete<T>(val document: T) : StaticDocumentDecode<T>
    data object Rejected : StaticDocumentDecode<Nothing>
}

private data class AdmittedHostedSources(val fileCount: Int, val setSha256: String)

private sealed interface HostedSourceAdmission {
    data class Complete(val sources: AdmittedHostedSources) : HostedSourceAdmission
    data class Rejected(val failure: StaticProofFailure) : HostedSourceAdmission
}

private val STATIC_PROOF_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

@CacheableTask
abstract class VerifyVfsPassiveReadTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val hostedSourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleGraphReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forbiddenEffectsReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val firewallReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginLayoutReport: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val sources = when (val admission = admitHostedSources(hostedSourceFiles.files)) {
            is HostedSourceAdmission.Complete -> admission.sources
            is HostedSourceAdmission.Rejected -> reject(admission.failure)
        }
        val inputs = listOf(
            read(moduleGraphReport.get().asFile.toPath()),
            read(forbiddenEffectsReport.get().asFile.toPath()),
            read(firewallReport.get().asFile.toPath()),
            read(pluginLayoutReport.get().asFile.toPath()),
        )
        val rejection = inputs.filterIsInstance<StaticInputRead.Rejected>().firstOrNull()
        if (rejection != null) reject(rejection.failure)
        val raw = inputs.filterIsInstance<StaticInputRead.Complete>().map { it.raw }
        val proof = when (val admission = admitStaticProof(raw, sources)) {
            is StaticProofAdmission.Complete -> admission.proof
            is StaticProofAdmission.Qualified -> reject(admission.failure)
            is StaticProofAdmission.Rejected -> reject(admission.failure)
        }
        val target = reportFile.get().asFile.toPath()
        try {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                STATIC_PROOF_JSON.encodeToString(VfsPassiveStaticProofDocument.serializer(), proof) + "\n",
                StandardCharsets.UTF_8,
            )
        } catch (_: IOException) {
            reject(StaticProofFailure.REPORT_WRITE_REJECTED)
        } catch (_: SecurityException) {
            reject(StaticProofFailure.REPORT_WRITE_REJECTED)
        }
        logger.lifecycle(
            "KVP-032 admitted {} hosted classes across {} transitive artifacts",
            proof.scannedClassCount,
            proof.transitiveArtifactCount,
        )
    }

    private fun reject(failure: StaticProofFailure): Nothing =
        throw GradleException("KVP-032 static VFS-passive proof rejected: $failure")
}

private sealed interface StaticInputRead {
    data class Complete(val raw: String) : StaticInputRead
    data class Rejected(val failure: StaticProofFailure) : StaticInputRead
}

/**
 * Proof transition: `Path -> StaticInputRead`.
 *
 * Establishes a bounded UTF-8 proof input or a closed [StaticProofFailure]. Raw path extraction is
 * permitted only in the owning Gradle task boundary.
 */
private fun read(path: Path): StaticInputRead = try {
    if (!Files.isRegularFile(path) || Files.size(path) > 1_048_576L) {
        StaticInputRead.Rejected(StaticProofFailure.INPUT_UNREADABLE)
    } else {
        StaticInputRead.Complete(Files.readString(path, StandardCharsets.UTF_8))
    }
} catch (_: IOException) {
    StaticInputRead.Rejected(StaticProofFailure.INPUT_UNREADABLE)
} catch (_: SecurityException) {
    StaticInputRead.Rejected(StaticProofFailure.INPUT_UNREADABLE)
}

/**
 * Proof transition: `List<String> -> StaticProofAdmission`.
 *
 * Establishes that the canonical module graph, compiled-effect scan, IDE-read firewall, and
 * physical plugin/transitive-classpath scan all completed with zero violations. Schema or policy
 * gaps remain closed [StaticProofFailure] values. Raw JSON is accepted only at this boundary.
 */
private fun admitStaticProof(
    raw: List<String>,
    sources: AdmittedHostedSources,
): StaticProofAdmission {
    if (raw.size != 4) return StaticProofAdmission.Rejected(StaticProofFailure.INPUT_UNREADABLE)
    val module = when (val decoded = decode<ArchitectureGateDocument>(raw[0])) {
        is StaticDocumentDecode.Complete -> decoded.document
        StaticDocumentDecode.Rejected -> return malformed()
    }
    val effects = when (val decoded = decode<ArchitectureGateDocument>(raw[1])) {
        is StaticDocumentDecode.Complete -> decoded.document
        StaticDocumentDecode.Rejected -> return malformed()
    }
    val firewall = when (val decoded = decode<FirewallReportDocument>(raw[2])) {
        is StaticDocumentDecode.Complete -> decoded.document
        StaticDocumentDecode.Rejected -> return malformed()
    }
    val layout = when (val decoded = decode<PluginLayoutReportDocument>(raw[3])) {
        is StaticDocumentDecode.Complete -> decoded.document
        StaticDocumentDecode.Rejected -> return malformed()
    }
    if (listOf(module, effects).any {
        it.schemaVersion != 1 || it.status != "ACCEPTED" || it.findings.isNotEmpty()
    }) return StaticProofAdmission.Rejected(StaticProofFailure.ARCHITECTURE_GATE_REJECTED)
    val requiredModules = setOf(":ide-plugin", ":runtime:ide-read", ":workspace:intellij-read")
    if (
        firewall.schemaVersion != 2 || firewall.taskId != "KVP-009" ||
        firewall.role != "IDE_READ_ONLY" || firewall.stage != "RUNTIME_SPLIT" ||
        firewall.modulePolicies.mapTo(linkedSetOf()) { it.module } != requiredModules ||
        firewall.modulePolicies.any { it.lifecycle != "ACTIVE" } ||
        firewall.forbiddenAuthorities.map { it.authority }.toSet().size != 9 ||
        firewall.forbiddenAuthorities.any { it.effects.isEmpty() }
    ) return StaticProofAdmission.Rejected(StaticProofFailure.FIREWALL_REJECTED)
    if (
        layout.schemaVersion != 1 || layout.taskId != "KVP-011" ||
        layout.policyId != "IDE_HOSTED_READ_ONLY_CLASSPATH" || layout.violationCount != 0 ||
        layout.scannedClassCount <= 0 || layout.nestedJars.isEmpty() ||
        layout.artifact.sizeBytes > layout.pluginSizeCeilingBytes ||
        layout.nestedJars.sumOf { it.classCount } != layout.scannedClassCount ||
        layout.nestedJars.any { it.classCount <= 0 || it.sha256.length != 64 }
    ) return StaticProofAdmission.Rejected(StaticProofFailure.PLUGIN_LAYOUT_REJECTED)
    val evidence = listOf(
        StaticProofEvidenceDocument("MODULE_GRAPH", sha256(raw[0]), module.findings.size, StaticProofOutcome.COMPLETE),
        StaticProofEvidenceDocument("COMPILED_EFFECTS", sha256(raw[1]), effects.findings.size, StaticProofOutcome.COMPLETE),
        StaticProofEvidenceDocument("HOSTED_SOURCE_SYMBOLS", sources.setSha256, sources.fileCount, StaticProofOutcome.COMPLETE),
        StaticProofEvidenceDocument("IDE_READ_FIREWALL", sha256(raw[2]), firewall.forbiddenAuthorities.size, StaticProofOutcome.COMPLETE),
        StaticProofEvidenceDocument("PLUGIN_TRANSITIVE_CLASSPATH", sha256(raw[3]), layout.scannedClassCount, StaticProofOutcome.COMPLETE),
    )
    return StaticProofAdmission.Complete(VfsPassiveStaticProofDocument(
        schemaVersion = 1,
        taskId = "KVP-032",
        publicInterface = "VfsPassiveStaticProof",
        outcome = StaticProofOutcome.COMPLETE,
        hostedModuleCount = firewall.modulePolicies.size,
        forbiddenAuthorityCount = firewall.forbiddenAuthorities.size,
        sourceFileCount = sources.fileCount,
        sourceSetSha256 = sources.setSha256,
        scannedClassCount = layout.scannedClassCount,
        transitiveArtifactCount = layout.nestedJars.size,
        violationCount = 0,
        evidence = evidence,
    ))
}

/**
 * Proof transition: `Set<File> -> HostedSourceAdmission`.
 *
 * Establishes a nonempty deterministic hosted-source closure with none of the fixed refresh,
 * import, repository-walk, source-hash, blocking-read, process-launch, or blocking-wait symbols.
 * An unreadable, empty, or forbidden source remains closed [StaticProofFailure] data. Raw source
 * extraction is permitted only at this build-policy boundary.
 */
private fun admitHostedSources(files: Set<java.io.File>): HostedSourceAdmission {
    if (files.isEmpty()) return HostedSourceAdmission.Rejected(StaticProofFailure.INPUT_UNREADABLE)
    val digests = mutableListOf<String>()
    files.sortedBy { it.path }.forEach { file ->
        val raw = try {
            if (!file.isFile || file.length() > 1_048_576L) {
                return HostedSourceAdmission.Rejected(StaticProofFailure.INPUT_UNREADABLE)
            }
            file.readText(StandardCharsets.UTF_8)
        } catch (_: IOException) {
            return HostedSourceAdmission.Rejected(StaticProofFailure.INPUT_UNREADABLE)
        } catch (_: SecurityException) {
            return HostedSourceAdmission.Rejected(StaticProofFailure.INPUT_UNREADABLE)
        }
        if (FORBIDDEN_HOSTED_SOURCE_SYMBOLS.any(raw::contains)) {
            return HostedSourceAdmission.Rejected(StaticProofFailure.ARCHITECTURE_GATE_REJECTED)
        }
        digests += sha256(raw)
    }
    return HostedSourceAdmission.Complete(
        AdmittedHostedSources(digests.size, sha256(digests.sorted().joinToString("\n"))),
    )
}

/** Raw JSON boundary `String -> StaticDocumentDecode<T>`; malformed input remains closed data. */
private inline fun <reified T> decode(raw: String): StaticDocumentDecode<T> = try {
    StaticDocumentDecode.Complete(STATIC_PROOF_JSON.decodeFromString<T>(raw))
} catch (_: SerializationException) {
    StaticDocumentDecode.Rejected
} catch (_: IllegalArgumentException) {
    StaticDocumentDecode.Rejected
}

private fun malformed() =
    StaticProofAdmission.Rejected(StaticProofFailure.MALFORMED_DOCUMENT)

private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private val FORBIDDEN_HOSTED_SOURCE_SYMBOLS = listOf(
    "VirtualFileManager.getInstance().syncRefresh",
    "refreshAndFindFileBy",
    "LocalFileSystem.getInstance().refresh",
    "ExternalSystemUtil.refresh",
    "ImportSpecBuilder(",
    "Files.walk(",
    "Files.walkFileTree(",
    "MessageDigest.getInstance(",
    "ProcessBuilder(",
    "runBlocking(",
    "ReadAction.compute<",
    "ApplicationManager.getApplication().runReadAction",
)
