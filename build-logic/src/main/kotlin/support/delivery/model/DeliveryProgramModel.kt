package support.delivery

import java.security.MessageDigest

@DslMarker
annotation class DeliveryProgramDsl

private val programIdPattern = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
private val taskIdPattern = Regex("KVP-[0-9]{3}")
private val requirementIdPattern = Regex("KVP-REQ-[0-9]{3}")
private val moduleIdPattern = Regex(":[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)*")
private val classificationIdPattern = Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")
private val gateIdPattern = Regex("KVP-[0-9]{3}-(?:RED|GREEN|COMPLETE-GATE)")
private val receiptIdPattern = Regex("KVP-[0-9]{3}-(?:RED-RECEIPT|GREEN-RECEIPT|COMPLETE)")
private val exactRevisionPattern = Regex("[0-9a-f]{40}")
private val sha256Pattern = Regex("[0-9a-f]{64}")

sealed interface DeliveryFailure

enum class DeliveryModelFailure : DeliveryFailure {
    INVALID_PROGRAM_ID, INVALID_TASK_ID, INVALID_REQUIREMENT_ID,
    INVALID_MODULE_ID, INVALID_AUTHORITY_ID, INVALID_EFFECT_ID,
    INVALID_COST_ID, INVALID_GATE_ID, INVALID_RECEIPT_ID,
    INVALID_GENERATION, INVALID_SHA256, EMPTY_EVIDENCE, EMPTY_LIMITATIONS,
}

sealed interface DeliveryRefinement<out T> {
    data class Complete<T>(val value: T) : DeliveryRefinement<T>
    data class Rejected(val failure: DeliveryModelFailure) : DeliveryRefinement<Nothing>
}

/** Proof transition: authored `String -> ProgramId`; establishes canonical identity; rejects invalid authoring; raw text exits only at projections. */
@JvmInline value class ProgramId internal constructor(val value: String) { init { require(programIdPattern.matches(value)) } }
/** Proof transition: authored `String -> TaskId`; establishes `KVP-NNN`; expected raw failure uses [refineTaskId]; raw text exits only at boundaries. */
@JvmInline value class TaskId internal constructor(val value: String) : Comparable<TaskId> {
    init { require(taskIdPattern.matches(value)) }
    override fun compareTo(other: TaskId) = value.compareTo(other.value)
}
/** Proof transition: authored `String -> RequirementId`; establishes `KVP-REQ-NNN`; rejects invalid authoring; raw text exits only at projections. */
@JvmInline value class RequirementId internal constructor(val value: String) { init { require(requirementIdPattern.matches(value)) } }
/** Proof transition: authored `String -> ModuleId`; establishes a canonical Gradle path; rejects invalid authoring; raw text exits only at projections. */
@JvmInline value class ModuleId internal constructor(val value: String) { init { require(moduleIdPattern.matches(value)) } }
/** Proof transition: authored `String -> AuthorityId`; establishes authority identity; expected raw failure uses [refineAuthorityId]; raw text exits only at projections. */
@JvmInline value class AuthorityId internal constructor(val value: String) { init { require(classificationIdPattern.matches(value)) } }
/** Proof transition: authored `String -> EffectId`; establishes effect identity; expected raw failure uses [refineEffectId]; raw text exits only at projections. */
@JvmInline value class EffectId internal constructor(val value: String) { init { require(classificationIdPattern.matches(value)) } }
/** Proof transition: authored `String -> CostId`; establishes cost identity; expected raw failure uses [refineCostId]; raw text exits only at projections. */
@JvmInline value class CostId internal constructor(val value: String) { init { require(classificationIdPattern.matches(value)) } }
/** Proof transition: authored `String -> GateId`; establishes gate identity; expected raw failure uses [refineGateId]; raw text exits only at boundaries. */
@JvmInline value class GateId internal constructor(val value: String) { init { require(gateIdPattern.matches(value)) } }
/** Proof transition: authored `String -> ReceiptId`; establishes receipt identity; expected raw failure uses [refineReceiptId]; raw text exits only at boundaries. */
@JvmInline value class ReceiptId internal constructor(val value: String) { init { require(receiptIdPattern.matches(value)) } }
/** Proof transition: authored `String -> DeliveryGeneration`; establishes exact Git identity; expected raw failure uses [refineDeliveryGeneration]; raw text exits only at boundaries. */
@JvmInline value class DeliveryGeneration internal constructor(val value: String) { init { require(exactRevisionPattern.matches(value)) } }
/** Proof transition: checked or computed `String -> Sha256`; establishes lowercase SHA-256 identity; rejects invalid authoring; raw text exits only at boundaries. */
@JvmInline value class Sha256 internal constructor(val value: String) { init { require(sha256Pattern.matches(value)) } }

private inline fun <T> refine(
    value: String,
    failure: DeliveryModelFailure,
    predicate: (String) -> Boolean,
    complete: (String) -> T,
): DeliveryRefinement<T> = if (predicate(value)) {
    DeliveryRefinement.Complete(complete(value))
} else {
    DeliveryRefinement.Rejected(failure)
}

/**
 * Proof transition: `String -> DeliveryRefinement<TaskId>`.
 * Establishes the canonical `KVP-NNN` identity. Failure is finite [DeliveryModelFailure]; raw
 * extraction is permitted only at program projection and Gradle configuration boundaries.
 */
fun refineTaskId(value: String): DeliveryRefinement<TaskId> =
    refine(value, DeliveryModelFailure.INVALID_TASK_ID, taskIdPattern::matches, ::TaskId)

/**
 * Proof transition: `String -> DeliveryRefinement<DeliveryGeneration>`.
 * Establishes one lowercase 40-hex Git revision. Failure is finite [DeliveryModelFailure]; raw
 * extraction is permitted only at Git and projection boundaries.
 */
fun refineDeliveryGeneration(value: String): DeliveryRefinement<DeliveryGeneration> =
    refine(value, DeliveryModelFailure.INVALID_GENERATION, exactRevisionPattern::matches, ::DeliveryGeneration)

/**
 * Proof transition: `String -> DeliveryRefinement<AuthorityId>`.
 * Establishes a nonempty canonical authority classification. Failure is finite
 * [DeliveryModelFailure]; raw extraction is permitted only at projection boundaries.
 */
fun refineAuthorityId(value: String): DeliveryRefinement<AuthorityId> =
    refine(value, DeliveryModelFailure.INVALID_AUTHORITY_ID, classificationIdPattern::matches, ::AuthorityId)

/**
 * Proof transition: `String -> DeliveryRefinement<EffectId>`.
 * Establishes a nonempty canonical effect classification. Failure is finite
 * [DeliveryModelFailure]; raw extraction is permitted only at projection boundaries.
 */
fun refineEffectId(value: String): DeliveryRefinement<EffectId> =
    refine(value, DeliveryModelFailure.INVALID_EFFECT_ID, classificationIdPattern::matches, ::EffectId)

/**
 * Proof transition: `String -> DeliveryRefinement<CostId>`.
 * Establishes a nonempty canonical cost classification. Failure is finite [DeliveryModelFailure];
 * raw extraction is permitted only at projection boundaries.
 */
fun refineCostId(value: String): DeliveryRefinement<CostId> =
    refine(value, DeliveryModelFailure.INVALID_COST_ID, classificationIdPattern::matches, ::CostId)

/**
 * Proof transition: `String -> DeliveryRefinement<GateId>`.
 * Establishes a task-bound RED, GREEN, or completion-gate identity. Failure is finite
 * [DeliveryModelFailure]; raw extraction is permitted only at Gradle and projection boundaries.
 */
fun refineGateId(value: String): DeliveryRefinement<GateId> =
    refine(value, DeliveryModelFailure.INVALID_GATE_ID, gateIdPattern::matches, ::GateId)

/**
 * Proof transition: `String -> DeliveryRefinement<ReceiptId>`.
 * Establishes a task-bound gate or completion receipt identity. Failure is finite
 * [DeliveryModelFailure]; raw extraction is permitted only at receipt and projection boundaries.
 */
fun refineReceiptId(value: String): DeliveryRefinement<ReceiptId> =
    refine(value, DeliveryModelFailure.INVALID_RECEIPT_ID, receiptIdPattern::matches, ::ReceiptId)

enum class EvidenceKind { DECLARED_INPUT, GATE_OBSERVATION, PROOF_ARTIFACT, PROOF_RECEIPT }

data class Evidence(val kind: EvidenceKind, val digest: Sha256)

class EvidenceSet private constructor(val values: List<Evidence>) {
    companion object {
        /**
         * Proof transition: `List<Evidence> -> DeliveryRefinement<EvidenceSet>`.
         * Establishes nonempty evidence while preserving every supplied proof. Failure is finite
         * [DeliveryModelFailure]; raw list extraction is permitted only at projection boundaries.
         */
        fun refine(values: List<Evidence>): DeliveryRefinement<EvidenceSet> =
            if (values.isEmpty()) {
                DeliveryRefinement.Rejected(DeliveryModelFailure.EMPTY_EVIDENCE)
            } else {
                DeliveryRefinement.Complete(EvidenceSet(values.toList()))
            }
    }
}

class NonEmptyLimitations private constructor(val values: List<String>) {
    companion object {
        /**
         * Proof transition: `List<String> -> DeliveryRefinement<NonEmptyLimitations>`.
         * Establishes at least one nonblank qualification. Failure is finite
         * [DeliveryModelFailure]; raw text extraction is permitted only at projection boundaries.
         */
        fun refine(values: List<String>): DeliveryRefinement<NonEmptyLimitations> =
            if (values.isEmpty() || values.any(String::isBlank)) {
                DeliveryRefinement.Rejected(DeliveryModelFailure.EMPTY_LIMITATIONS)
            } else {
                DeliveryRefinement.Complete(NonEmptyLimitations(values.toList()))
            }
    }
}

sealed interface Outcome<out T, out F : DeliveryFailure> {
    data class Complete<T>(val value: T, val evidence: EvidenceSet) : Outcome<T, Nothing>
    data class Qualified<T>(
        val value: T,
        val evidence: EvidenceSet,
        val limitations: NonEmptyLimitations,
    ) : Outcome<T, Nothing>
    data class Rejected<F : DeliveryFailure>(
        val failure: F,
        val evidence: EvidenceSet,
    ) : Outcome<Nothing, F>
}

sealed interface TaskProgression {
    val taskId: TaskId

    data class Blocked(
        override val taskId: TaskId,
        val missingReceipts: Set<ReceiptId>,
    ) : TaskProgression { init { require(missingReceipts.isNotEmpty()) } }
    data class Ready(override val taskId: TaskId) : TaskProgression
    data class Invalid(
        override val taskId: TaskId,
        val failure: DeliveryModelFailure,
    ) : TaskProgression
    data class Proven(
        override val taskId: TaskId,
        val completionReceipt: ReceiptId,
    ) : TaskProgression
}

enum class EdgeKind { REQUIRES_ALL, REQUIRES_ONE, JOINS_SELECTED_LANE, RETIRES, INVALIDATES, RECOVERS_TO }
enum class GateKind { RED, GREEN, TASK_COMPLETION, ACCEPTANCE, REVIEW, REVALIDATION, TERMINAL }

data class DependencyExpression(val kind: EdgeKind, val taskIds: Set<TaskId>)
data class TaskOutput(val id: String, val kind: String, val path: String, val description: String)
data class ProofCommand(val gateId: String, val command: String, val expectation: String) {
    val identity = GateId(gateId)
    init { require(command.isNotBlank() && expectation.isNotBlank()) }
}
data class CompletionReceiptContract(val receiptId: String, val requiredGateIds: Set<String>, val dependencyReceiptIds: Set<String>, val outputPath: String) {
    val identity = ReceiptId(receiptId)
    val requiredGates = requiredGateIds.mapTo(linkedSetOf(), ::GateId)
    val dependencyReceipts = dependencyReceiptIds.mapTo(linkedSetOf(), ::ReceiptId)
    init { require(outputPath.isNotBlank()) }
}

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
) {
    val costClassifications = costs.mapTo(linkedSetOf(), ::CostId)
}

data class ModuleBoundary(val id: ModuleId, val lifecycle: String, val role: String, val owns: List<String>, val dependencies: Set<ModuleId>, val authorities: Set<AuthorityId>, val effects: Set<EffectId>)
data class AuthorityOwnership(val id: AuthorityId, val owner: ModuleId, val fact: String)
data class EffectOwnership(val id: EffectId, val owners: Set<ModuleId>, val purpose: String)
data class Requirement(val id: RequirementId, val statement: String)
data class SpecialEdge(val kind: EdgeKind, val from: String, val target: String, val result: String?)
data class ProcessNode(val id: String, val kind: String)
data class ProcessTransition(val from: String, val to: String, val transition: String, val failure: String)
data class GateNode(val id: String, val taskId: TaskId, val kind: GateKind, val command: String, val statement: String, val dependencyReceiptIds: Set<String>, val outputReceiptId: String) {
    val identity = GateId(id)
    val dependencyReceipts = dependencyReceiptIds.mapTo(linkedSetOf(), ::ReceiptId)
    val outputReceipt = ReceiptId(outputReceiptId)
}
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
    val generation = DeliveryGeneration(targetHead)

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
        require(generation.value == targetHead)
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
