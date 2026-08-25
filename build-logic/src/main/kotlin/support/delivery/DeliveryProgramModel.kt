package support.delivery

import java.security.MessageDigest

@DslMarker annotation class DeliveryProgramDsl
@JvmInline value class ProgramId(val value: String)
@JvmInline value class TaskId(val value: String) : Comparable<TaskId> { override fun compareTo(other: TaskId) = value.compareTo(other.value) }
@JvmInline value class RequirementId(val value: String)
@JvmInline value class ModuleId(val value: String)
@JvmInline value class AuthorityId(val value: String)
@JvmInline value class EffectId(val value: String)
@JvmInline value class Sha256(val value: String)
@JvmInline internal value class AuthorityGitRevision internal constructor(val value: String)
@JvmInline internal value class ProgramFingerprint internal constructor(val value: String)
@JvmInline internal value class RequirementFingerprint internal constructor(val value: String)
@JvmInline internal value class AuthoritySourceId internal constructor(val value: String)
@JvmInline internal value class AuthoritySourcePath internal constructor(val value: String)
@JvmInline internal value class AuthorityArtifactDigest internal constructor(val value: String)

internal enum class AuthorityContradiction {
    PROGRAM_TARGET_IS_BASE_REVISION_NOT_SELF_HASHING_COMMIT, ABSOLUTE_SOURCE_INPUTS_MUST_EXIST,
    CHECKED_IN_RECEIPT_CANNOT_BIND_ITS_OWN_COMMIT, PROGRESSION_ENGINE_WAS_DECLARED_BUT_ABSENT,
    LEGACY_TWO_PROCESS_TERMINAL_CONFLICTS_WITH_IDE_HOSTED_TERMINAL,
}
internal enum class ObsoleteAuthorityAssumption {
    EXACTLY_TWO_RUNTIME_PROCESSES, PACKAGED_INDEXER_TERMINAL_ACCEPTANCE,
    FOREGROUND_IDE_CONTROLS_SEMANTIC_BACKEND,
}
internal enum class UnprovenAuthorityClaim {
    SUPPORTED_IDE_ENDPOINT_COMPATIBILITY, FORBIDDEN_EFFECT_ABSENCE, EPOCH_MOVEMENT_COVERAGE,
    INSTALLED_OPERATION_CHAIN, CLEAN_CHECKOUT_AND_HOME_ACCEPTANCE, EXACT_HEAD_CI, INDEPENDENT_REVIEW,
}

internal data class ProgramAuthorityDocument(
    val schemaVersion: Int, val baseRevision: String, val exactHead: String,
    val programFingerprint: String, val requirementFingerprint: String,
    val sourceArtifacts: List<AuthoritySourceDocument>,
    val contradictions: Set<AuthorityContradiction>, val obsoleteAssumptions: Set<ObsoleteAuthorityAssumption>,
    val unprovenClaims: Set<UnprovenAuthorityClaim>,
)

internal data class AuthoritySourceDocument(val id: String, val path: String, val sha256: String)

internal class ProgramAuthorityExpectation private constructor(
    val baseRevision: AuthorityGitRevision, val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint, val requirementFingerprint: RequirementFingerprint,
    val sourceDigests: Map<AuthoritySourceId, AuthorityArtifactDigest>,
    val allowedReads: Set<AuthoritySourcePath>,
) {
    companion object {
        /**
         * Proof transition: raw Gradle authority inputs -> `ProgramAuthorityExpectation`.
         *
         * Establishes full Git and SHA-256 identities, unique non-blank source IDs, and non-blank
         * allowed paths. Expected malformed input returns [ProgramAuthorityExpectationResult.Rejected].
         * Raw values may be extracted only by the Gradle task configuration boundary.
         */
        fun parse(
            baseRevision: String,
            exactHead: String,
            programFingerprint: String,
            requirementFingerprint: String,
            sourceDigests: Map<String, String>,
            allowedReads: List<String>,
        ): ProgramAuthorityExpectationResult {
            if (!baseRevision.isGitRevision() || !exactHead.isGitRevision()) {
                return ProgramAuthorityExpectationResult.Rejected(AuthorityAdmissionFailure.MalformedGitRevision)
            }
            if (!programFingerprint.isSha256() || !requirementFingerprint.isSha256()) {
                return ProgramAuthorityExpectationResult.Rejected(AuthorityAdmissionFailure.MalformedFingerprint)
            }
            if (sourceDigests.isEmpty() || sourceDigests.any { (id, digest) -> id.isBlank() || !digest.isSha256() }) {
                return ProgramAuthorityExpectationResult.Rejected(AuthorityAdmissionFailure.MalformedSourceDeclaration)
            }
            if (allowedReads.any(String::isBlank)) {
                return ProgramAuthorityExpectationResult.Rejected(AuthorityAdmissionFailure.MalformedSourcePath)
            }
            return ProgramAuthorityExpectationResult.Complete(
                ProgramAuthorityExpectation(
                    AuthorityGitRevision(baseRevision),
                    AuthorityGitRevision(exactHead),
                    ProgramFingerprint(programFingerprint),
                    RequirementFingerprint(requirementFingerprint),
                    sourceDigests.map { (id, digest) ->
                        AuthoritySourceId(id) to AuthorityArtifactDigest(digest)
                    }.toMap(),
                    allowedReads.mapTo(mutableSetOf(), ::AuthoritySourcePath),
                ),
            )
        }
    }
}

internal sealed interface ProgramAuthorityExpectationResult {
    data class Complete(val expectation: ProgramAuthorityExpectation) : ProgramAuthorityExpectationResult; data class Rejected(val failure: AuthorityAdmissionFailure) : ProgramAuthorityExpectationResult
}
internal sealed interface AuthoritySourceObservation {
    data class Complete(val digest: AuthorityArtifactDigest) : AuthoritySourceObservation; data class Rejected(val failure: AuthoritySourceFailure) : AuthoritySourceObservation
}
internal enum class AuthoritySourceFailure { MISSING, NOT_REGULAR, SYMLINK, TOO_LARGE, READ_FAILED }
internal sealed interface AuthorityAdmissionFailure {
    data object MalformedDocument : AuthorityAdmissionFailure
    data object UnsupportedSchema : AuthorityAdmissionFailure
    data object MalformedGitRevision : AuthorityAdmissionFailure
    data object MalformedFingerprint : AuthorityAdmissionFailure
    data object MalformedSourceDeclaration : AuthorityAdmissionFailure
    data object MalformedSourcePath : AuthorityAdmissionFailure
    data object BaseRevisionMismatch : AuthorityAdmissionFailure
    data object ExactHeadMismatch : AuthorityAdmissionFailure
    data object ProgramFingerprintMismatch : AuthorityAdmissionFailure
    data object RequirementFingerprintMismatch : AuthorityAdmissionFailure
    data object SourceSetMismatch : AuthorityAdmissionFailure
    data class SourcePathNotAllowed(val sourceId: AuthoritySourceId) : AuthorityAdmissionFailure
    data class SourceUnavailable(val sourceId: AuthoritySourceId, val failure: AuthoritySourceFailure) : AuthorityAdmissionFailure
    data class SourceDigestMismatch(val sourceId: AuthoritySourceId) : AuthorityAdmissionFailure
    data object ContradictionSetIncomplete : AuthorityAdmissionFailure
    data object ObsoleteAssumptionSetIncomplete : AuthorityAdmissionFailure
    data object UnprovenClaimSetIncomplete : AuthorityAdmissionFailure
}
internal class AdmittedProgramAuthority internal constructor(
    val exactHead: AuthorityGitRevision, val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
    val sourceDigests: Map<AuthoritySourceId, AuthorityArtifactDigest>,
    val contradictionProjection: String,
)
internal sealed interface ProgramAuthorityAdmission {
    data class Complete(val authority: AdmittedProgramAuthority) : ProgramAuthorityAdmission; data class Rejected(val failure: AuthorityAdmissionFailure) : ProgramAuthorityAdmission
}
/**
 * Proof transition: parsed authority document plus an expectation and source observations ->
 * `AdmittedProgramAuthority`.
 *
 * Establishes exact revision and fingerprint identity, the complete declared source set and byte
 * digests, and exhaustive contradiction, obsolete-assumption, and unproven-claim sets. Every
 * expected failure returns [ProgramAuthorityAdmission.Rejected]. Raw paths remain inside the
 * Gradle authority task boundary.
 */
internal fun admitProgramAuthority(
    document: ProgramAuthorityDocument,
    expectation: ProgramAuthorityExpectation,
    observeSource: (AuthoritySourcePath) -> AuthoritySourceObservation,
): ProgramAuthorityAdmission {
    if (document.schemaVersion != 1) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.UnsupportedSchema)
    if (!document.baseRevision.isGitRevision() || document.baseRevision != expectation.baseRevision.value) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.BaseRevisionMismatch)
    if (!document.exactHead.isGitRevision() || document.exactHead != expectation.exactHead.value) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.ExactHeadMismatch)
    if (!document.programFingerprint.isSha256() || document.programFingerprint != expectation.programFingerprint.value) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.ProgramFingerprintMismatch)
    if (!document.requirementFingerprint.isSha256() || document.requirementFingerprint != expectation.requirementFingerprint.value) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.RequirementFingerprintMismatch)
    if (document.sourceArtifacts.map { it.id }.toSet().size != document.sourceArtifacts.size) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.MalformedSourceDeclaration)
    if (document.sourceArtifacts.map { AuthoritySourceId(it.id) }.toSet() != expectation.sourceDigests.keys) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourceSetMismatch)
    for (source in document.sourceArtifacts) {
        val sourceId = AuthoritySourceId(source.id)
        val sourcePath = AuthoritySourcePath(source.path)
        val expectedDigest = expectation.sourceDigests.getValue(sourceId)
        if (sourcePath !in expectation.allowedReads) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourcePathNotAllowed(sourceId))
        if (!source.sha256.isSha256() || source.sha256 != expectedDigest.value) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourceDigestMismatch(sourceId))
        when (val observation = observeSource(sourcePath)) {
            is AuthoritySourceObservation.Complete -> if (observation.digest != expectedDigest) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourceDigestMismatch(sourceId))
            is AuthoritySourceObservation.Rejected -> return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.SourceUnavailable(sourceId, observation.failure))
        }
    }
    if (document.contradictions != AuthorityContradiction.entries.toSet()) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.ContradictionSetIncomplete)
    if (document.obsoleteAssumptions != ObsoleteAuthorityAssumption.entries.toSet()) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.ObsoleteAssumptionSetIncomplete)
    if (document.unprovenClaims != UnprovenAuthorityClaim.entries.toSet()) return ProgramAuthorityAdmission.Rejected(AuthorityAdmissionFailure.UnprovenClaimSetIncomplete)
    return ProgramAuthorityAdmission.Complete(
        AdmittedProgramAuthority(
            expectation.exactHead,
            expectation.programFingerprint,
            expectation.requirementFingerprint,
            expectation.sourceDigests,
            document.contradictionProjection(),
        ),
    )
}

internal fun ProgramAuthorityDocument.contradictionProjection(): String = buildString {
    appendLine("# VFS-passive authority contradictions"); appendLine(); appendLine("## Contradictions")
    contradictions.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
    appendLine(); appendLine("## Obsolete assumptions")
    obsoleteAssumptions.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
    appendLine(); appendLine("## Unproven claims")
    unprovenClaims.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
}

private fun String.isGitRevision(): Boolean = matches(Regex("[0-9a-f]{40}"))
private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

sealed interface Outcome<out T> {
    data class Complete<T>(val value: T, val evidence: Evidence) : Outcome<T>
    data class Qualified<T>(val value: T, val evidence: Evidence, val limitations: List<String>) : Outcome<T> { init { require(limitations.isNotEmpty()) } }
    data class Rejected(val failure: String, val evidence: Evidence) : Outcome<Nothing>
}

data class Evidence(val kind: String, val digest: Sha256)

enum class EdgeKind { REQUIRES_ALL, REQUIRES_ONE, JOINS_SELECTED_LANE, RETIRES, INVALIDATES, RECOVERS_TO }
enum class GateKind { RED, GREEN, TASK_COMPLETION, ACCEPTANCE, REVIEW, REVALIDATION, TERMINAL }

data class DependencyExpression(val kind: EdgeKind, val taskIds: Set<TaskId>)
data class TaskOutput(val id: String, val kind: String, val path: String, val description: String)
data class ProofCommand(val gateId: String, val command: String, val expectation: String)
data class CompletionReceiptContract(val receiptId: String, val requiredGateIds: Set<String>, val dependencyReceiptIds: Set<String>, val outputPath: String)

data class TaskNode(
    val id: TaskId,
    val title: String,
    val goal: String,
    val milestone: String,
    val dependencies: DependencyExpression,
    val allowedReads: List<String>,
    val allowedWrites: List<String>,
    val inputs: List<Map<String, String>>,
    val outputs: List<TaskOutput>,
    val publicInterface: String,
    val internalImplementation: String,
    val effects: Set<EffectId>,
    val costs: Set<String>,
    val forbiddenWork: List<String>,
    val red: ProofCommand,
    val green: ProofCommand,
    val reviewBoundary: String,
    val completionReceipt: CompletionReceiptContract,
    val provesRequirements: Set<RequirementId>,
    val authorities: Set<AuthorityId>,
)

data class ModuleBoundary(val id: ModuleId, val lifecycle: String, val role: String, val owns: List<String>, val dependencies: Set<ModuleId>, val authorities: Set<AuthorityId>, val effects: Set<EffectId>)
data class AuthorityOwnership(val id: AuthorityId, val owner: ModuleId, val fact: String)
data class EffectOwnership(val id: EffectId, val owners: Set<ModuleId>, val purpose: String)
data class Requirement(val id: RequirementId, val statement: String)
data class SpecialEdge(val kind: EdgeKind, val from: String, val target: String, val result: String?)
data class ProcessNode(val id: String, val kind: String)
data class ProcessTransition(val from: String, val to: String, val transition: String, val failure: String)
data class GateNode(val id: String, val taskId: TaskId, val kind: GateKind, val command: String, val statement: String, val dependencyReceiptIds: Set<String>, val outputReceiptId: String)
data class MetricRequirement(val id: String, val predicate: String, val value: Any)

data class DeliveryProgram(
    val schemaVersion: Int,
    val id: ProgramId,
    val name: String,
    val targetHead: String,
    val requirementFingerprint: Sha256,
    val sourceDigests: Map<String, Sha256>,
    val requirements: List<Requirement>,
    val modules: List<ModuleBoundary>,
    val authorities: List<AuthorityOwnership>,
    val effects: List<EffectOwnership>,
    val tasks: List<TaskNode>,
    val specialEdges: List<SpecialEdge>,
    val processNodes: List<ProcessNode>,
    val processTransitions: List<ProcessTransition>,
    val gates: List<GateNode>,
    val installedMetrics: List<MetricRequirement>,
    val terminalTask: TaskId,
) {
    /**
     * Proof transition: `DeliveryProgram -> ValidatedProgram`.
     *
     * Establishes identifier uniqueness, graph closure and acyclicity, derived
     * topological waves, terminal reachability, requirement coverage, and proof
     * gate consistency. Invalid definitions are authoring defects at the Gradle
     * build-policy boundary; raw program fields are projected only by
     * [ValidatedProgram.projection].
     */
    fun validate(): ValidatedProgram {
        require(schemaVersion == 1)
        require(targetHead.matches(Regex("[0-9a-f]{40}")))
        require(requirementFingerprint.value.matches(Regex("[0-9a-f]{64}")))
        require(tasks.map { it.id }.toSet().size == tasks.size)
        require(requirements.map { it.id }.toSet().size == requirements.size)
        require(modules.map { it.id }.toSet().size == modules.size)
        val byId = tasks.associateBy { it.id }
        tasks.forEach { task ->
            require(task.title.isNotBlank() && task.goal.isNotBlank())
            require(task.allowedReads.isNotEmpty() && task.allowedWrites.isNotEmpty())
            require(task.inputs.isNotEmpty() && task.outputs.isNotEmpty())
            require(task.forbiddenWork.isNotEmpty())
            require(task.dependencies.kind == EdgeKind.REQUIRES_ALL)
            require(task.dependencies.taskIds.all(byId::containsKey))
            require(task.id !in task.dependencies.taskIds)
            require(task.red.command.isNotBlank() && task.green.command.isNotBlank())
            require(task.completionReceipt.requiredGateIds == setOf(task.red.gateId, task.green.gateId))
        }
        val order = topologicalOrder(byId)
        require(terminalTask in byId)
        require(order.last() == terminalTask)
        val waves = mutableMapOf<TaskId, Int>()
        order.forEach { id ->
            val deps = byId.getValue(id).dependencies.taskIds
            waves[id] = if (deps.isEmpty()) 0 else 1 + deps.maxOf { waves.getValue(it) }
        }
        val reqIds = requirements.mapTo(mutableSetOf()) { it.id }
        require(tasks.flatMap { it.provesRequirements }.toSet().containsAll(reqIds))
        require(gates.map { it.id }.toSet().size == gates.size)
        return ValidatedProgram(this, order, waves)
    }

    private fun topologicalOrder(byId: Map<TaskId, TaskNode>): List<TaskId> {
        val indegree = byId.keys.associateWith { 0 }.toMutableMap()
        val outgoing = byId.keys.associateWith { mutableListOf<TaskId>() }.toMutableMap()
        byId.values.forEach { task -> task.dependencies.taskIds.forEach { dep -> indegree[task.id] = indegree.getValue(task.id) + 1; outgoing.getValue(dep).add(task.id) } }
        val ready = java.util.PriorityQueue<TaskId>(); indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<TaskId>()
        while (ready.isNotEmpty()) { val current = ready.remove(); order += current; outgoing.getValue(current).sorted().forEach { next -> indegree[next] = indegree.getValue(next) - 1; if (indegree.getValue(next) == 0) ready += next } }
        require(order.size == byId.size) { "delivery graph contains a cycle" }
        return order
    }
}

data class ValidatedProgram(val program: DeliveryProgram, val order: List<TaskId>, val waves: Map<TaskId, Int>) {
    fun projection(): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "schemaVersion" to program.schemaVersion,
            "programId" to program.id.value,
            "name" to program.name,
            "targetHead" to program.targetHead,
            "requirementFingerprint" to program.requirementFingerprint.value,
            "sourceDigests" to program.sourceDigests.mapKeys { it.key }.mapValues { it.value.value },
            "requirements" to program.requirements.sortedBy { it.id.value }.map { mapOf("id" to it.id.value, "statement" to it.statement) },
            "modules" to program.modules.sortedBy { it.id.value }.map { m -> mapOf("id" to m.id.value, "lifecycle" to m.lifecycle, "role" to m.role, "owns" to m.owns.sorted(), "dependencies" to m.dependencies.map { it.value }.sorted(), "authorities" to m.authorities.map { it.value }.sorted(), "effects" to m.effects.map { it.value }.sorted()) },
            "authorities" to program.authorities.sortedBy { it.id.value }.map { mapOf("id" to it.id.value, "owner" to it.owner.value, "fact" to it.fact) },
            "effects" to program.effects.sortedBy { it.id.value }.map { mapOf("id" to it.id.value, "owners" to it.owners.map { o -> o.value }.sorted(), "purpose" to it.purpose) },
            "tasks" to program.tasks.sortedBy { it.id }.map { t ->
                mapOf(
                    "id" to t.id.value, "title" to t.title, "goal" to t.goal, "milestone" to t.milestone,
                    "dependencyExpression" to mapOf("kind" to "allOf", "taskIds" to t.dependencies.taskIds.map { it.value }.sorted()),
                    "allowedReads" to t.allowedReads, "allowedWrites" to t.allowedWrites, "inputs" to t.inputs,
                    "outputs" to t.outputs.map { mapOf("id" to it.id, "kind" to it.kind, "path" to it.path, "description" to it.description) },
                    "publicInterface" to t.publicInterface, "internalImplementation" to t.internalImplementation,
                    "effectClassification" to t.effects.map { it.value }.sorted(), "costClassification" to t.costs.sorted(),
                    "forbiddenWork" to t.forbiddenWork,
                    "red" to mapOf("gateId" to t.red.gateId, "command" to t.red.command, "expectedFailure" to t.red.expectation),
                    "green" to mapOf("gateId" to t.green.gateId, "command" to t.green.command, "expectedProof" to t.green.expectation),
                    "reviewBoundary" to t.reviewBoundary,
                    "completionReceipt" to mapOf("receiptId" to t.completionReceipt.receiptId, "requiredGateIds" to t.completionReceipt.requiredGateIds.sorted(), "requiredDependencyReceipts" to t.completionReceipt.dependencyReceiptIds.sorted(), "outputPath" to t.completionReceipt.outputPath),
                    "provesRequirements" to t.provesRequirements.map { it.value }.sorted(), "authorities" to t.authorities.map { it.value }.sorted(),
                    "computedWave" to waves.getValue(t.id),
                )
            },
            "taskOrder" to order.map { it.value },
            "waveCount" to (waves.values.maxOrNull()!! + 1),
            "specialEdges" to program.specialEdges.map { mapOf("kind" to it.kind.name.lowercase(), "from" to it.from, "target" to it.target, "result" to it.result) },
            "processGraph" to mapOf("nodes" to program.processNodes.map { mapOf("id" to it.id, "kind" to it.kind) }, "transitions" to program.processTransitions.map { mapOf("from" to it.from, "to" to it.to, "transition" to it.transition, "failure" to it.failure) }),
            "gateGraph" to program.gates.sortedBy { it.id }.map { mapOf("id" to it.id, "taskId" to it.taskId.value, "kind" to it.kind.name, "command" to it.command, "statement" to it.statement, "dependsOnReceiptIds" to it.dependencyReceiptIds.sorted(), "outputReceiptId" to it.outputReceiptId) },
            "installedAcceptance" to mapOf("ownerTask" to "KVP-034", "report" to "build/reports/ide-hosted/KVP-034-installed.json", "requiredMetrics" to program.installedMetrics.map { mapOf("id" to it.id, "predicate" to it.predicate, "value" to it.value) }),
            "terminal" to mapOf("taskId" to program.terminalTask.value, "type" to "BestCaseVfsPassiveReusedIndex", "receiptPath" to "build/reports/ide-hosted/best-case-vfs-passive-reused-index.receipt.json", "derivedOnly" to true),
        )
        val fingerprint = sha256(canonicalJson(base))
        return linkedMapOf<String, Any?>("programFingerprint" to fingerprint.value).apply { putAll(base) }
    }

    fun requirementTraceProjection(): Map<String, Any?> {
        val orderedTasks = program.tasks.sortedBy { it.id }
        val entries = program.requirements.sortedBy { it.id.value }.map { requirement ->
            val implementationTasks = orderedTasks.filter { requirement.id in it.provesRequirements }
            mapOf(
                "requirementId" to requirement.id.value,
                "statement" to requirement.statement,
                "implementationTaskIds" to implementationTasks.map { it.id.value },
                "enforcementGateIds" to implementationTasks.flatMap {
                    listOf(it.red.gateId, it.green.gateId)
                },
                "finalRevalidationTaskId" to "KVP-042",
                "proofStateSource" to "ADMITTED_RECEIPTS",
            )
        }
        return mapOf(
            "schemaVersion" to 1,
            "programFingerprint" to projection().getValue("programFingerprint"),
            "entries" to entries,
        )
    }
}

fun sha256(value: String): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Sha256(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
}

fun canonicalJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(prefix = "{", postfix = "}") { canonicalJson(it.key.toString()) + ":" + canonicalJson(it.value) }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
    else -> error("unsupported canonical JSON value: ${value::class}")
}
