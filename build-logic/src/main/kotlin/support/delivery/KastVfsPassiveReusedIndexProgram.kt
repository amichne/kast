package support.delivery

object KastVfsPassiveReusedIndexProgram {
    const val TARGET_HEAD = "78262728313c90bb847e73425dc1a76d704397db"
    val SUPERSEDED_REQUIREMENT_FINGERPRINT =
        Sha256("55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e")
    val REQUIREMENT_FINGERPRINT =
        Sha256("de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c")
    internal val persistedGoalSourcePath =
        AuthoritySourcePath("gradle/delivery/authority-sources/persisted-goal.txt")
    internal val authoritySourceCandidates = listOf(
        persistedGoalSourcePath,
        AuthoritySourcePath(
            "gradle/delivery/authority-sources/superseded-clean-slate-task-graph.json",
        ),
        AuthoritySourcePath(
            "gradle/delivery/authority-sources/superseded-clean-slate-plan.md",
        ),
        AuthoritySourcePath("gradle/delivery/authority-sources/intellij-substrate-program.html"),
    )
    internal val authorityLedgerOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-authority-ledger.json")
    internal val authorityContradictionOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-contradictions.md")
    internal val authorityVerificationOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-authority.json")

    val definition: DeliveryProgram = DeliveryProgram(
        schemaVersion = 1,
        id = ProgramId("kast-vfs-passive-reused-index"),
        name = "Kast best-case VFS-passive reused-index delivery program",
        targetHead = TARGET_HEAD,
        requirementFingerprint = REQUIREMENT_FINGERPRINT,
        sourceDigests = mapOf(
            "deliveryAuthority" to
                Sha256("de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"),
            "intellijSubstrateProgram" to
                Sha256("7827929f5b8e0bb4248d2135a7382834045c8158cec2a55c2a1933a7220a6b50"),
            "supersededCleanSlateGraph" to
                Sha256("a926effde75fa956c85e33180f77d0cdbdeaf1980ae37259eb2234b9e3ae200c"),
            "supersededCleanSlatePlan" to
                Sha256("797c16ff7264010723e9b7bb2a4e02fe276cb7788bbaea6c5595c295d7a5e361"),
        ),
        requirements = deliveryRequirements(),
        modules = deliveryModules(),
        authorities = deliveryAuthorities() + AuthorityOwnership(
            AuthorityId("DISTRIBUTION_IDENTITY"),
            ModuleId(":distribution:contract"),
            "Installed product and runtime compatibility identity.",
        ),
        effects = deliveryEffects() + listOf(
            EffectOwnership(EffectId("PURE"), emptySet(), "Deterministic computation without effects."),
            EffectOwnership(
                EffectId("METADATA_READ"),
                setOf(ModuleId(":build-logic")),
                "Read bounded delivery-authority metadata.",
            ),
            EffectOwnership(
                EffectId("REVIEW"),
                setOf(ModuleId(":build-logic")),
                "Evaluate admitted delivery evidence without product mutation.",
            ),
        ),
        tasks = deliveryTasksM0M1() + deliveryTasksM2() + deliveryTasksM3M5(),
        specialEdges = deliverySpecialEdges(),
        processNodes = deliveryProcessNodes(),
        processTransitions = deliveryProcessTransitions(),
        installedMetrics = deliveryInstalledMetrics(),
        deliveryBatches = listOf(
            hostedProductionCompositionDeliveryBatch(),
            defaultHostedReleaseDeliveryBatch(),
        ),
        terminalTask = TaskId("KVP-043"),
    )

    val validated: ValidatedProgram by lazy {
        when (val admission = admitCanonicalProgram(definition)) {
            is CanonicalProgramAdmission.Complete -> admission.program
            is CanonicalProgramAdmission.Rejected ->
                error("canonical delivery program rejected: ${admission.failure}")
        }
    }
}

internal fun hostedProductionCompositionBatch(): DeliveryBatch =
    KastVfsPassiveReusedIndexProgram.validated.program.deliveryBatches.single {
        it.id == DeliveryBatchId("hosted-production-composition")
    }

internal fun hostedProductionCompositionOwnedWrites(taskId: TaskId): List<String> =
    hostedProductionCompositionBatch().tasks.single { it.taskId == taskId }.ownedWrites

internal fun hostedProductionCompositionCompanionWrites(taskId: TaskId): List<String> {
    val program = KastVfsPassiveReusedIndexProgram.validated.program
    return hostedProductionCompositionBatch().tasks
        .filterNot { it.taskId == taskId }
        .flatMap { owned ->
            program.tasks.single { it.id == owned.taskId }.allowedWrites
        }
        .distinct()
}

internal fun defaultHostedReleaseBatch(): DeliveryBatch =
    KastVfsPassiveReusedIndexProgram.validated.program.deliveryBatches.single {
        it.id == DeliveryBatchId("default-hosted-release")
    }

enum class CanonicalProgramFailure : DeliveryFailure {
    UNSUPPORTED_SCHEMA,
    DUPLICATE_TASK,
    DUPLICATE_REQUIREMENT,
    DUPLICATE_MODULE,
    DUPLICATE_AUTHORITY_OWNER,
    DUPLICATE_EFFECT,
    DUPLICATE_PROCESS_NODE,
    DUPLICATE_GATE,
    INCOMPLETE_PROGRAM,
    INCOMPLETE_REQUIREMENT,
    INCOMPLETE_MODULE_BOUNDARY,
    INCOMPLETE_AUTHORITY,
    INCOMPLETE_EFFECT,
    INCOMPLETE_PROCESS_GRAPH,
    INCOMPLETE_TASK_CONTRACT,
    UNKNOWN_TASK_DEPENDENCY,
    SELF_DEPENDENCY,
    UNSUPPORTED_DEPENDENCY,
    CYCLE,
    UNKNOWN_TERMINAL,
    MULTIPLE_TERMINALS,
    UNTRACED_REQUIREMENT,
    UNKNOWN_MODULE_DEPENDENCY,
    UNKNOWN_AUTHORITY_OWNER,
    UNKNOWN_EFFECT_OWNER,
    UNKNOWN_TASK_CLASSIFICATION,
    UNKNOWN_PROCESS_NODE,
    GATE_CONTRACT_MISMATCH,
    DUPLICATE_DELIVERY_BATCH,
    INCOMPLETE_DELIVERY_BATCH,
    UNKNOWN_DELIVERY_BATCH_TASK,
    DELIVERY_BATCH_WRITE_OUTSIDE_TASK_SCOPE,
    DELIVERY_BATCH_WRITE_SCOPE_CONFLICT,
}

sealed interface CanonicalProgramAdmission {
    data class Complete(val program: ValidatedProgram) : CanonicalProgramAdmission
    data class Rejected(val failure: CanonicalProgramFailure) : CanonicalProgramAdmission
}

private sealed interface CanonicalOrderResult {
    data class Complete(val order: List<TaskId>, val outgoing: Map<TaskId, Set<TaskId>>) :
        CanonicalOrderResult
    data object Rejected : CanonicalOrderResult
}

/**
 * Proof transition: `DeliveryProgram -> CanonicalProgramAdmission`.
 * Establishes complete task contracts; closed task, module, authority, effect, process, gate, and
 * requirement references; deterministic acyclic order and waves; and exactly one reachable
 * terminal. Expected failure is finite [CanonicalProgramFailure]. Raw program fields are consumed
 * only here and emitted by the existing deterministic projection boundary.
 */
fun admitCanonicalProgram(candidate: DeliveryProgram): CanonicalProgramAdmission {
    if (candidate.schemaVersion != 1) return rejected(CanonicalProgramFailure.UNSUPPORTED_SCHEMA)
    if (candidate.name.isBlank() || candidate.sourceDigests.isEmpty()) {
        return rejected(CanonicalProgramFailure.INCOMPLETE_PROGRAM)
    }
    if (candidate.tasks.map { it.id }.toSet().size != candidate.tasks.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_TASK)
    }
    if (candidate.requirements.map { it.id }.toSet().size != candidate.requirements.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_REQUIREMENT)
    }
    if (candidate.modules.map { it.id }.toSet().size != candidate.modules.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_MODULE)
    }
    if (candidate.authorities.map { it.id }.toSet().size != candidate.authorities.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_AUTHORITY_OWNER)
    }
    if (candidate.effects.map { it.id }.toSet().size != candidate.effects.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_EFFECT)
    }
    if (candidate.processNodes.map { it.id }.toSet().size != candidate.processNodes.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_PROCESS_NODE)
    }
    if (candidate.gates.map { it.id }.toSet().size != candidate.gates.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_GATE)
    }
    if (candidate.requirements.any { it.statement.isBlank() }) {
        return rejected(CanonicalProgramFailure.INCOMPLETE_REQUIREMENT)
    }
    if (candidate.modules.any {
            it.lifecycle.isBlank() || it.role.isBlank() || it.owns.isEmpty() ||
                it.owns.any(String::isBlank)
        }
    ) return rejected(CanonicalProgramFailure.INCOMPLETE_MODULE_BOUNDARY)
    if (candidate.authorities.any { it.fact.isBlank() }) {
        return rejected(CanonicalProgramFailure.INCOMPLETE_AUTHORITY)
    }
    if (candidate.effects.any { it.purpose.isBlank() }) {
        return rejected(CanonicalProgramFailure.INCOMPLETE_EFFECT)
    }
    if (candidate.processNodes.any { it.id.isBlank() || it.kind.isBlank() } ||
        candidate.processTransitions.any {
            it.from.isBlank() || it.to.isBlank() || it.transition.isBlank() || it.failure.isBlank()
        }
    ) return rejected(CanonicalProgramFailure.INCOMPLETE_PROCESS_GRAPH)

    val taskIds = candidate.tasks.mapTo(linkedSetOf()) { it.id }
    if (candidate.tasks.any { task ->
            task.title.isBlank() || task.goal.isBlank() || task.milestone.isBlank() ||
                task.allowedReads.isEmpty() || task.allowedWrites.isEmpty() ||
                task.inputs.isEmpty() || task.outputs.isEmpty() ||
                task.inputs.any { input ->
                    input.keys != setOf("id", "kind") || input.values.any(String::isBlank)
                } ||
                task.outputs.any { it.id.isBlank() || it.path.isBlank() } ||
                task.publicInterface.isBlank() || task.internalImplementation.isBlank() ||
                task.effects.isEmpty() || task.costs.isEmpty() || task.forbiddenWork.isEmpty() ||
                task.reviewBoundary.isBlank()
        }
    ) {
        return rejected(CanonicalProgramFailure.INCOMPLETE_TASK_CONTRACT)
    }
    if (candidate.tasks.any { it.dependencies.kind != EdgeKind.REQUIRES_ALL }) {
        return rejected(CanonicalProgramFailure.UNSUPPORTED_DEPENDENCY)
    }
    if (candidate.tasks.any { !taskIds.containsAll(it.dependencies.taskIds) }) {
        return rejected(CanonicalProgramFailure.UNKNOWN_TASK_DEPENDENCY)
    }
    if (candidate.tasks.any { it.id in it.dependencies.taskIds }) {
        return rejected(CanonicalProgramFailure.SELF_DEPENDENCY)
    }
    if (candidate.deliveryBatches.map { it.id }.toSet().size != candidate.deliveryBatches.size) {
        return rejected(CanonicalProgramFailure.DUPLICATE_DELIVERY_BATCH)
    }
    val tasksById = candidate.tasks.associateBy { it.id }
    candidate.deliveryBatches.forEach { batch ->
        if (batch.tasks.isEmpty() || batch.tasks.map { it.taskId }.toSet().size != batch.tasks.size ||
            batch.tasks.any { it.ownedWrites.isEmpty() || it.ownedWrites.any(String::isBlank) }
        ) return rejected(CanonicalProgramFailure.INCOMPLETE_DELIVERY_BATCH)
        if (batch.tasks.any { it.taskId !in taskIds }) {
            return rejected(CanonicalProgramFailure.UNKNOWN_DELIVERY_BATCH_TASK)
        }
        if (batch.tasks.any { owned ->
                val declared = tasksById.getValue(owned.taskId).allowedWrites
                owned.ownedWrites.any { scope -> declared.none { scope.inDeliveryScope(it) } }
            }
        ) return rejected(CanonicalProgramFailure.DELIVERY_BATCH_WRITE_OUTSIDE_TASK_SCOPE)
        val ownership = batch.tasks.flatMap { owned ->
            owned.ownedWrites.map { scope -> owned.taskId to scope }
        }
        if (ownership.indices.any { left ->
                (left + 1 until ownership.size).any { right ->
                    ownership[left].first != ownership[right].first &&
                        ownership[left].second.overlapsDeliveryScope(ownership[right].second)
                }
            }
        ) return rejected(CanonicalProgramFailure.DELIVERY_BATCH_WRITE_SCOPE_CONFLICT)
    }
    if (candidate.terminalTask !in taskIds) {
        return rejected(CanonicalProgramFailure.UNKNOWN_TERMINAL)
    }
    val byId = candidate.tasks.associateBy { it.id }
    val ordering = when (val result = deriveCanonicalOrder(byId)) {
        is CanonicalOrderResult.Complete -> result
        CanonicalOrderResult.Rejected -> return rejected(CanonicalProgramFailure.CYCLE)
    }
    val terminals = ordering.outgoing.filterValues { it.isEmpty() }.keys
    if (terminals != setOf(candidate.terminalTask)) {
        return rejected(CanonicalProgramFailure.MULTIPLE_TERMINALS)
    }

    val requirementIds = candidate.requirements.mapTo(linkedSetOf()) { it.id }
    val tracedRequirements = candidate.tasks.flatMapTo(linkedSetOf()) { it.provesRequirements }
    if (tracedRequirements != requirementIds) {
        return rejected(CanonicalProgramFailure.UNTRACED_REQUIREMENT)
    }
    val moduleIds = candidate.modules.mapTo(linkedSetOf()) { it.id }
    if (candidate.modules.any { !moduleIds.containsAll(it.dependencies) }) {
        return rejected(CanonicalProgramFailure.UNKNOWN_MODULE_DEPENDENCY)
    }
    val authorities = candidate.authorities.associateBy { it.id }
    if (candidate.authorities.any { it.owner !in moduleIds } ||
        candidate.modules.any { module ->
            module.authorities.any { it !in authorities }
        }
    ) return rejected(CanonicalProgramFailure.UNKNOWN_AUTHORITY_OWNER)
    val effects = candidate.effects.associateBy { it.id }
    if (candidate.effects.any { !moduleIds.containsAll(it.owners) } ||
        candidate.modules.any { module ->
            module.effects.any { it !in effects }
        }
    ) return rejected(CanonicalProgramFailure.UNKNOWN_EFFECT_OWNER)
    if (candidate.tasks.any { !authorities.keys.containsAll(it.authorities) ||
            !effects.keys.containsAll(it.effects)
        }
    ) return rejected(CanonicalProgramFailure.UNKNOWN_TASK_CLASSIFICATION)

    val processIds = candidate.processNodes.mapTo(linkedSetOf()) { it.id }
    if (candidate.processTransitions.any { it.from !in processIds || it.to !in processIds }) {
        return rejected(CanonicalProgramFailure.UNKNOWN_PROCESS_NODE)
    }
    val gateMismatch = candidate.tasks.any { task ->
        val dependencyReceipts = task.dependencies.taskIds.mapTo(linkedSetOf()) {
            "${it.value}-COMPLETE"
        }
        when (val proof = task.proof) {
            is TaskProofProtocol.Legacy ->
                proof.completion.requiredGateIds != setOf(
                    proof.red.gateId,
                    proof.green.gateId,
                ) || proof.completion.dependencyReceiptIds != dependencyReceipts ||
                    proof.gates.size != 3
            is TaskProofProtocol.Atomic ->
                proof.receipt.dependencies.mapTo(linkedSetOf()) { it.value } !=
                    dependencyReceipts || proof.gates.size != 1 ||
                    proof.command.command.isBlank() ||
                    proof.command.misuse.command.isBlank() ||
                    proof.command.legalPath.command.isBlank()
        }
    } || candidate.gates.any { gate ->
        gate.command.isBlank() || gate.statement.isBlank()
    }
    if (gateMismatch) {
        return rejected(CanonicalProgramFailure.GATE_CONTRACT_MISMATCH)
    }

    val waves = buildMap<TaskId, Int> {
        ordering.order.forEach { taskId ->
            val dependencies = byId.getValue(taskId).dependencies.taskIds
            put(
                taskId,
                if (dependencies.isEmpty()) {
                    0
                } else {
                    1 + dependencies.maxOf { dependency -> getValue(dependency) }
                },
            )
        }
    }
    return CanonicalProgramAdmission.Complete(
        ValidatedProgram(candidate, ordering.order, waves),
    )
}

private fun String.inDeliveryScope(parent: String) = this == parent || startsWith("$parent/")
private fun String.overlapsDeliveryScope(other: String) = inDeliveryScope(other) || other.inDeliveryScope(this)

private fun rejected(failure: CanonicalProgramFailure) =
    CanonicalProgramAdmission.Rejected(failure)

private fun deriveCanonicalOrder(byId: Map<TaskId, TaskNode>): CanonicalOrderResult {
    val indegree = byId.keys.associateWith { 0 }.toMutableMap()
    val outgoing = byId.keys.associateWith { linkedSetOf<TaskId>() }.toMutableMap()
    byId.values.forEach { task ->
        task.dependencies.taskIds.forEach { predecessor ->
            indegree[task.id] = indegree.getValue(task.id) + 1
            outgoing.getValue(predecessor) += task.id
        }
    }
    val ready = java.util.PriorityQueue<TaskId>().apply {
        addAll(indegree.filterValues { it == 0 }.keys)
    }
    val order = mutableListOf<TaskId>()
    while (ready.isNotEmpty()) {
        val current = ready.remove()
        order += current
        outgoing.getValue(current).sorted().forEach { next ->
            indegree[next] = indegree.getValue(next) - 1
            if (indegree.getValue(next) == 0) ready += next
        }
    }
    return if (order.size == byId.size) {
        CanonicalOrderResult.Complete(order, outgoing.mapValues { it.value.toSet() })
    } else {
        CanonicalOrderResult.Rejected
    }
}
