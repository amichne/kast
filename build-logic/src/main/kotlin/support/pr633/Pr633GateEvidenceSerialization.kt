package support.pr633

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

internal val pr633AuthorityJson = Json {
    ignoreUnknownKeys = true
}

internal val pr633EvidenceJson = Json {
    prettyPrint = true
}

@Serializable
internal data class Pr633EventDocument(
    val number: Int,
    @SerialName("pull_request")
    val pullRequest: PullRequestDocument,
) {
    @Serializable
    data class PullRequestDocument(
        val base: BaseDocument,
        val head: HeadDocument,
    )

    @Serializable
    data class BaseDocument(val ref: String)

    @Serializable
    data class HeadDocument(val sha: String)
}

@Serializable
internal data class Pr633ProgramDocument(
    val schemaVersion: Int,
    val programId: String,
    val tasks: List<TaskDocument>,
    val gates: List<GateDocument>,
) {
    @Serializable
    data class TaskDocument(
        val id: String,
        val allowedWrites: List<String>,
    )

    @Serializable
    data class GateDocument(
        val id: String,
        val dependsOn: List<String>,
        val dependencyRevisionPolicy: DependencyRevisionPolicyDocument,
    )
}

@Serializable
internal enum class DependencyRevisionPolicyDocument {
    SAME_HEAD,
    MERGED_PREDECESSOR_ANCESTOR,
}

@Serializable
internal data class Pr633PathPolicyDocument(
    val schemaVersion: Int,
    val programId: String,
    val policy: String,
    val forbiddenPrefixes: List<String>,
)

@Serializable
internal data class StackVerificationReportDocument(
    val schemaVersion: Int,
    val pullRequest: Int,
    val baseRef: String,
    val headSha: String,
    val mainRef: String,
    val mainSha: String,
    val changedPaths: List<String>,
    val status: Pr633EvidenceStatus,
)

@Serializable
internal data class GitDiffVerificationReportDocument(
    val schemaVersion: Int,
    val mainSha: String,
    val headSha: String,
    val status: Pr633EvidenceStatus,
)

@Serializable
internal data class ExternalPr633EvidenceDocument(
    val schemaVersion: Int,
    val kind: String,
    val status: Pr633EvidenceStatus,
    val headSha: String,
    val facts: Map<String, String>,
)

@Serializable
internal data class Pr633GateEvidenceDocument(
    val schemaVersion: Int,
    val programId: String,
    val programFingerprint: String,
    val gateId: String,
    val headSha: String,
    val status: Pr633EvidenceStatus,
    val dependencyEvidence: Map<String, String>,
    val facts: Map<String, String>,
    val checks: List<Pr633GateCheckDocument>,
)

@Serializable
internal data class Pr633GateCheckDocument(
    val id: String,
    val status: Pr633EvidenceStatus,
    val detail: String? = null,
)

@Serializable
internal enum class Pr633EvidenceStatus {
    @SerialName("passed")
    PASSED,
}

/**
 * Writes one deterministic proof record after all checks for a program gate pass.
 *
 * The task refuses dependency reports from another program projection. SAME_HEAD gates also
 * refuse another repository head. Cross-pull-request gates admit a different head only through the
 * program's explicit merged-predecessor policy and its separate ancestry verifier.
 */
@CacheableTask
abstract class WritePr633GateEvidenceTask : DefaultTask() {
    init {
        facts.convention(emptyMap())
        expectedExternalFacts.convention(emptyMap())
        externalEvidenceBindsHead.convention(true)
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val programFile: RegularFileProperty

    @get:Input
    abstract val gateId: Property<String>

    @get:Input
    @get:Optional
    abstract val headSha: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val externalEvidenceFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val expectedExternalKind: Property<String>

    @get:Input
    abstract val expectedExternalFacts: MapProperty<String, String>

    @get:Input
    abstract val externalEvidenceBindsHead: Property<Boolean>

    @get:Input
    abstract val checkIds: ListProperty<String>

    @get:Input
    abstract val facts: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyReports: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun writeEvidence() {
        val programBytes = programFile.get().asFile.readBytes()
        val program = pr633AuthorityJson.decodeFromString(
            Pr633ProgramDocument.serializer(),
            programBytes.decodeToString(),
        )
        check(program.schemaVersion == 2) {
            "Unsupported PR 633 program schema version ${program.schemaVersion}"
        }
        val programId = program.programId
        val programFingerprint = sha256(programBytes)
        val externalEvidence = if (externalEvidenceFile.isPresent) {
            pr633EvidenceJson.decodeFromString(
                ExternalPr633EvidenceDocument.serializer(),
                externalEvidenceFile.get().asFile.readText(),
            ).also { evidence ->
                check(evidence.schemaVersion == 1) {
                    "Unsupported external evidence schema version ${evidence.schemaVersion}"
                }
                if (expectedExternalKind.isPresent) {
                    check(evidence.kind == expectedExternalKind.get()) {
                        "Expected external evidence kind ${expectedExternalKind.get()}, " +
                            "received ${evidence.kind}"
                    }
                }
                expectedExternalFacts.get().forEach { (name, expected) ->
                    check(evidence.facts[name] == expected) {
                        "External evidence fact '$name' is ${evidence.facts[name]}, not $expected"
                    }
                }
            }
        } else {
            null
        }
        val externalHead = externalEvidence?.headSha
        val currentHead = (
            if (externalEvidence != null && externalEvidenceBindsHead.get()) {
                externalHead
            } else {
                headSha.orNull
            }
        )?.also(::requireGitSha)
            ?: error("Gate ${gateId.get()} has no exact head SHA")
        if (externalEvidence != null && externalEvidenceBindsHead.get() && headSha.isPresent) {
            check(headSha.get() == currentHead) {
                "Configured head ${headSha.get()} differs from external evidence $currentHead"
            }
        }
        val declaredGate = program.gates.singleOrNull { it.id == gateId.get() }
            ?: error("Gate ${gateId.get()} is absent from ${programFile.get().asFile}")

        val declaredDependencies = declaredGate.dependsOn.sorted()
        val dependencyRevisionPolicy = declaredGate.dependencyRevisionPolicy

        val dependencyEvidence = linkedMapOf<String, String>()
        dependencyReports.files.sortedBy { it.path }.forEach { file ->
            val bytes = file.readBytes()
            val report = pr633EvidenceJson.decodeFromString(
                Pr633GateEvidenceDocument.serializer(),
                bytes.decodeToString(),
            )
            check(report.schemaVersion == 1) {
                "$file uses unsupported gate evidence schema version ${report.schemaVersion}"
            }
            val dependencyGate = report.gateId
            check(report.programId == programId) {
                "$file belongs to another program"
            }
            check(report.programFingerprint == programFingerprint) {
                "$file belongs to another program projection"
            }
            if (dependencyRevisionPolicy == DependencyRevisionPolicyDocument.SAME_HEAD) {
                check(report.headSha == currentHead) {
                    "$file belongs to ${report.headSha}, not $currentHead"
                }
            }
            check(dependencyGate !in dependencyEvidence) {
                "Duplicate dependency evidence for $dependencyGate"
            }
            dependencyEvidence[dependencyGate] = sha256(bytes)
        }
        check(dependencyEvidence.keys.sorted() == declaredDependencies) {
            "Gate ${gateId.get()} requires $declaredDependencies but received ${dependencyEvidence.keys.sorted()}"
        }

        val checks = checkIds.get().distinct().sorted()
        check(checks.isNotEmpty()) { "Gate ${gateId.get()} has no checks" }
        val externalFacts = externalEvidence?.facts.orEmpty()
        val factValues = (externalFacts + facts.getOrElse(emptyMap())).toSortedMap()
        check(externalFacts.keys.intersect(facts.getOrElse(emptyMap()).keys).isEmpty()) {
            "Configured facts duplicate external evidence facts"
        }
        factValues.forEach { (name, value) ->
            check(name.isNotBlank()) { "Gate ${gateId.get()} has a blank fact name" }
            check(value.isNotBlank()) { "Gate ${gateId.get()} fact '$name' is blank" }
        }

        val document = Pr633GateEvidenceDocument(
            schemaVersion = 1,
            programId = programId,
            programFingerprint = programFingerprint,
            gateId = gateId.get(),
            headSha = currentHead,
            status = Pr633EvidenceStatus.PASSED,
            dependencyEvidence = dependencyEvidence.toSortedMap(),
            facts = factValues,
            checks = checks.map { checkId ->
                Pr633GateCheckDocument(checkId, Pr633EvidenceStatus.PASSED)
            },
        )
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            pr633EvidenceJson.encodeToString(Pr633GateEvidenceDocument.serializer(), document) +
                "\n",
        )
    }
}

private fun requireGitSha(value: String) {
    require(value.matches(Regex("[0-9a-f]{40}"))) {
        "Expected a full lowercase Git SHA, received '$value'"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
