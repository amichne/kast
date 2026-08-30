package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.change.apply.AddDeclarationApplyRequest
import io.github.amichne.kast.change.apply.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.AddDeclarationApplyService
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.RequestedMutationWriteScope
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddDeclarationPlanResult
import io.github.amichne.kast.change.contract.AddDeclarationPlanningEvidenceInput
import io.github.amichne.kast.change.contract.EditableMutationTarget
import io.github.amichne.kast.change.contract.MutationTargetObservation
import io.github.amichne.kast.change.contract.ObservedMutationTargetState
import io.github.amichne.kast.change.contract.domain
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentCompilation
import io.github.amichne.kast.change.contract.InstalledAddDeclarationIntentCompiler
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.verify.AddDeclarationVerificationEvidence
import io.github.amichne.kast.change.verify.ChangeVerificationObservation
import io.github.amichne.kast.change.verify.ChangeVerificationObservationRejection
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservation
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObserver
import io.github.amichne.kast.change.verify.PendingChangeVerification
import io.github.amichne.kast.change.verify.ResultingGenerationPublication
import io.github.amichne.kast.change.verify.ResultingGenerationPublisher
import io.github.amichne.kast.change.verify.VerifiedMutationRequest
import io.github.amichne.kast.change.verify.VerifiedMutationResult
import io.github.amichne.kast.change.verify.VerifiedReceipt
import io.github.amichne.kast.change.verify.VerifiedMutationService
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.service.DiagnosticService
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.sqlite.HostedDurableMutationAudit
import io.github.amichne.kast.evidence.sqlite.SqliteDurableChangeAuthority
import io.github.amichne.kast.evidence.sqlite.SqliteMutationRecoveryJournal
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.topology.build.VerifiedTopologyDeltaPublication
import io.github.amichne.kast.topology.build.VerifiedTopologyDeltaPublicationFailure
import io.github.amichne.kast.topology.build.VerifiedTopologyDeltaPublicationOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun interface ChangePlanOperations {
    suspend fun plan(selector: SymbolSelector, declaration: String): HostedChangePlanningResult
}

fun interface ChangeApplyOperations {
    fun apply(plan: AddDeclarationChangePlan): AddDeclarationApplyResult
}

fun interface ChangeVerifyOperations {
    suspend fun verify(pending: PendingChangeVerification): HostedChangeVerificationResult
}

sealed interface HostedChangeVerificationResult {
    data class Verified(val receipt: VerifiedReceipt) : HostedChangeVerificationResult
    data class MutationRejected(val result: VerifiedMutationResult) : HostedChangeVerificationResult
    data class TopologyRejected(
        val failure: VerifiedTopologyDeltaPublicationFailure,
    ) : HostedChangeVerificationResult
}

fun interface ChangeRecoveryOperations {
    fun recover(binding: MutationPlanBinding): AddDeclarationRecoveryOutcome
}

/** Publication authority retained through recovery after clean writer authority is withdrawn. */
interface HostedMutationPublicationOperations : ResultingGenerationPublisher {
    /** Publishes a proven successor of whatever workspace publication is current now. */
    fun publishCurrentTransition(): ResultingGenerationPublication
}

sealed interface HostedChangePlanningResult {
    data class Planned(val plan: AddDeclarationChangePlan) : HostedChangePlanningResult
    data class Rejected(val failure: HostedMutationAdmissionFailure) : HostedChangePlanningResult
}

enum class HostedMutationAdmissionFailure {
    WORKSPACE_NOT_READY,
    TOPOLOGY_BUILD_REQUIRED,
    SELECTOR_STALE,
    EDITABLE_TARGET_REQUIRED,
    INTENT_REJECTED,
    RELATION_READ_REQUIRED,
    TRAVERSAL_REQUIRED,
    DIAGNOSTIC_CHECK_REQUIRED,
    STORAGE_UNAVAILABLE,
    CORRUPT_RECOVERY,
}

sealed interface HostedMutationState {
    data class Clean(
        val planning: ChangePlanOperations,
        val application: ChangeApplyOperations,
        val verification: ChangeVerifyOperations,
        val recovery: ChangeRecoveryOperations,
        val publication: HostedMutationPublicationOperations,
    ) : HostedMutationState

    data class RecoveryRequired(
        val recovery: ChangeRecoveryOperations,
        val publication: HostedMutationPublicationOperations,
    ) : HostedMutationState

    data class Rejected(
        val failure: HostedMutationAdmissionFailure,
    ) : HostedMutationState
}

/** Rebuilds mutation authority from current durable state after a terminal recovery result. */
fun interface HostedMutationAdmissionOperations {
    fun admit(): HostedMutationState
}

data class HostedChangeRuntimePorts(
    val relationCompiler: RelationCompilerPort,
    val diagnosticCompiler: DiagnosticCompilerPort,
    val sourceObserver: AddDeclarationSourceObserver,
    val sourceWriter: AddDeclarationSourceWriter,
    val sourceRollback: AddDeclarationSourceRollback,
    val intentCompiler: InstalledAddDeclarationIntentCompiler,
    val semanticObserver: HostedAddDeclarationSemanticObserver,
)

object HostedMutationComposition {
    fun admit(
        workspace: HostedWorkspaceOperations,
        topology: HostedTopologyOperations,
        ports: HostedChangeRuntimePorts,
        journal: SqliteMutationRecoveryJournal,
        authority: SqliteDurableChangeAuthority,
        topologyPublisher: VerifiedTopologyDeltaPublicationOperations,
    ): HostedMutationState {
        val recoveryService = AddDeclarationRecoveryService(journal)
        val recovery = ChangeRecoveryOperations { binding ->
            recoveryService.recover(binding, rollback@{ record ->
                val plan = when (val loaded = authority.loadPlanForRecovery(binding)) {
                    is io.github.amichne.kast.change.verify.ChangePlanLookup.Found -> loaded.plan
                    io.github.amichne.kast.change.verify.ChangePlanLookup.Missing,
                    is io.github.amichne.kast.change.verify.ChangePlanLookup.Rejected,
                    -> return@rollback io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult.Rejected(
                        io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    )
                }
                val restored = when (val result = MutationAuthority.restore(plan, record)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return@rollback io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult.Rejected(
                        io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    )
                }
                ports.sourceRollback.rollback(restored, record)
            })
        }
        when (val audit = authority.auditMutationState()) {
            HostedDurableMutationAudit.Clean -> Unit
            is HostedDurableMutationAudit.RecoveryRequired ->
                return HostedMutationState.RecoveryRequired(recovery, workspace)
            is HostedDurableMutationAudit.Rejected -> return HostedMutationState.Rejected(
                if (audit.failure == io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure.CORRUPT_RECORD) {
                    HostedMutationAdmissionFailure.CORRUPT_RECOVERY
                } else {
                    HostedMutationAdmissionFailure.STORAGE_UNAVAILABLE
                },
            )
        }
        val relations = RelationService(workspace, ports.relationCompiler)
        val diagnostics = DiagnosticService(workspace, ports.diagnosticCompiler)
        val planner = HostedAddDeclarationPlanner(
            workspace,
            relations,
            topology.traversal,
            diagnostics,
            ports.sourceObserver,
            ports.intentCompiler,
        )
        val apply = AddDeclarationApplyService(
            recoveryService,
            ports.sourceObserver,
            ports.sourceWriter,
            ports.sourceRollback,
        )
        val verification = VerifiedMutationService(
            publisher = { prior ->
                val current = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
                if (current != null && current.generation.value > prior.generation.value) {
                    io.github.amichne.kast.change.verify.ResultingGenerationPublication.Published(current)
                } else {
                    io.github.amichne.kast.change.verify.ResultingGenerationPublication.Rejected(
                        io.github.amichne.kast.change.verify.ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
                    )
                }
            },
            observer = hostedVerificationObserver(
                workspace,
                relations,
                diagnostics,
                ports.sourceObserver,
                ports.semanticObserver,
            ),
        )
        return HostedMutationState.Clean(
            planner,
            ChangeApplyOperations { plan ->
                val current = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
                    ?: return@ChangeApplyOperations AddDeclarationApplyResult.Rejected(
                        io.github.amichne.kast.change.apply.AddDeclarationApplyFailure.Admission(
                            io.github.amichne.kast.change.apply.MutationAdmissionFailure.STALE_GENERATION,
                        ),
                    )
                apply.apply(
                    AddDeclarationApplyRequest(
                        plan,
                        current,
                        RequestedMutationWriteScope(
                            current.root,
                            plan.writes.entries.mapTo(linkedSetOf()) { it.source },
                        ),
                    ),
                )
            },
            { pending ->
                when (val result = verification.verify(
                    VerifiedMutationRequest(pending.plan, pending.application),
                )) {
                    is VerifiedMutationResult.Verified -> when (
                        val publication = topologyPublisher.publish(
                            pending.application.publication.plannedLease,
                            result.receipt.resultingWorkspace,
                            pending.application.source,
                            pending.application.postimage,
                        )
                    ) {
                        is VerifiedTopologyDeltaPublication.Published,
                        is VerifiedTopologyDeltaPublication.Unchanged,
                        -> HostedChangeVerificationResult.Verified(result.receipt)
                        is VerifiedTopologyDeltaPublication.Rejected ->
                            HostedChangeVerificationResult.TopologyRejected(publication.failure)
                    }
                    else -> HostedChangeVerificationResult.MutationRejected(result)
                }
            },
            recovery,
            workspace,
        )
    }
}

private class HostedAddDeclarationPlanner(
    private val workspace: HostedWorkspaceOperations,
    private val relations: RelationOperations,
    private val traversals: io.github.amichne.kast.traversal.contract.TraversalOperations,
    private val diagnostics: DiagnosticOperations,
    private val sources: AddDeclarationSourceObserver,
    private val intents: InstalledAddDeclarationIntentCompiler,
) : ChangePlanOperations {
    override suspend fun plan(
        selector: SymbolSelector,
        declaration: String,
    ): HostedChangePlanningResult {
        val published = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
            ?: return rejected(HostedMutationAdmissionFailure.WORKSPACE_NOT_READY)
        if (selector.lease != published.readLease) {
            return rejected(HostedMutationAdmissionFailure.SELECTOR_STALE)
        }
        val file = selector.file as? SymbolDiscoveryFileIdentity.Workspace
            ?: return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        val observed = when (val result = sources.observe(file)) {
            is SourceObservationResult.Observed -> result.source as? ObservedMutationSource
                ?: return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
            is SourceObservationResult.Rejected ->
                return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        if (observed.access != SourceWriteAccess.Writable) {
            return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        val targetPath = Path.of(file.path.value)
        val owner = published.sourceRoots.singleOrNull { root ->
            val path = Path.of(published.root.value).resolve(root.location.value).normalize()
            targetPath != path && targetPath.startsWith(path)
        }?.owner ?: return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        val target = when (val admitted = EditableMutationTarget.admit(
            MutationTargetObservation(
                published,
                selector,
                owner,
                ObservedMutationTargetState(published.readLease, file, observed.content),
            ),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(HostedMutationAdmissionFailure.EDITABLE_TARGET_REQUIRED)
        }
        val intent = when (val compiled = intents.compile(selector, declaration)) {
            is InstalledAddDeclarationIntentCompilation.Compiled -> compiled.intent
            is InstalledAddDeclarationIntentCompilation.Rejected ->
                return rejected(HostedMutationAdmissionFailure.INTENT_REJECTED)
        }
        val budgets = hostedMutationBudgets()
            ?: return rejected(HostedMutationAdmissionFailure.RELATION_READ_REQUIRED)
        val relation = relations.read(
            RelationRequest.start(selector, RelationMeaning.References, budgets.relation),
        ) as? RelationReadResult.Complete
            ?: return rejected(HostedMutationAdmissionFailure.RELATION_READ_REQUIRED)
        val traversalPlan = when (val admitted = TraversalPlan.start(
            selector,
            RelationMeaning.References,
            budgets.traversal,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(HostedMutationAdmissionFailure.TRAVERSAL_REQUIRED)
        }
        val traversal = when (val result = traversals.run(traversalPlan)) {
            is TraversalResult.Complete -> result
            is TraversalResult.Qualified ->
                return rejected(HostedMutationAdmissionFailure.TRAVERSAL_REQUIRED)
            is TraversalResult.Rejected -> return rejected(
                when (result.reason) {
                    io.github.amichne.kast.traversal.contract.TraversalRejection.RequiredEvidenceUnavailable,
                    io.github.amichne.kast.traversal.contract.TraversalRejection.RequiredEvidenceStale,
                    -> HostedMutationAdmissionFailure.TOPOLOGY_BUILD_REQUIRED
                    else -> HostedMutationAdmissionFailure.TRAVERSAL_REQUIRED
                },
            )
        }
        val scope = when (val admitted = DiagnosticScope.fromCanonicalPaths(
            published.readLease,
            listOf(Path.of(file.path.value)),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                return rejected(HostedMutationAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED)
        }
        val diagnostic = diagnostics.check(DiagnosticCheckRequest(scope))
            as? DiagnosticCheckResult.Complete
            ?: return rejected(HostedMutationAdmissionFailure.DIAGNOSTIC_CHECK_REQUIRED)
        return when (val planned = PureAddDeclarationPlanningService().plan(
            AddDeclarationPlanRequest(
                target,
                intent.declaration,
                intent.expectedDelta,
                AddDeclarationPlanningEvidenceInput(
                    listOf(relation),
                    listOf(traversal),
                    listOf(diagnostic),
                ),
            ),
        )) {
            is AddDeclarationPlanResult.Planned -> HostedChangePlanningResult.Planned(planned.plan)
            is AddDeclarationPlanResult.Rejected ->
                rejected(HostedMutationAdmissionFailure.INTENT_REJECTED)
        }
    }
}

private fun hostedVerificationObserver(
    workspace: HostedWorkspaceOperations,
    relations: RelationOperations,
    diagnostics: DiagnosticOperations,
    sources: AddDeclarationSourceObserver,
    semantic: HostedAddDeclarationSemanticObserver,
): ChangeVerificationObserver = ChangeVerificationObserver { request ->
    val plan = request.plan as? AddDeclarationChangePlan
        ?: return@ChangeVerificationObserver verificationRejected()
    val current = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
        ?: return@ChangeVerificationObserver verificationRejected(
            ChangeVerificationObservationRejection.RESULTING_GENERATION_MOVED,
        )
    val source = when (val observed = sources.observe(request.applied.source)) {
        is SourceObservationResult.Observed -> observed.source as? ObservedMutationSource
            ?: return@ChangeVerificationObserver verificationRejected()
        is SourceObservationResult.Rejected -> return@ChangeVerificationObserver verificationRejected()
    }
    val semanticEvidence = when (val observed = semantic.observe(current, plan)) {
        is HostedAddDeclarationSemanticObservation.Observed -> observed.evidence
        is HostedAddDeclarationSemanticObservation.Rejected ->
            return@ChangeVerificationObserver verificationRejected()
    }
    val budgets = hostedMutationBudgets() ?: return@ChangeVerificationObserver verificationRejected()
    val relationEvidence = plan.evidence.relations.map { planned ->
        awaitHostedRead {
            relations.read(
            RelationRequest.start(
                semanticEvidence.anchor,
                planned.meaning.domain(),
                budgets.relation,
            ),
            )
        } as? RelationReadResult.Complete
            ?: return@ChangeVerificationObserver verificationRejected()
    }
    val scope = when (val admitted = DiagnosticScope.fromCanonicalPaths(
        current.readLease,
        listOf(Path.of(request.applied.source.path.value)),
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return@ChangeVerificationObserver verificationRejected()
    }
    val diagnostic = awaitHostedRead { diagnostics.check(DiagnosticCheckRequest(scope)) }
        as? DiagnosticCheckResult.Complete
        ?: return@ChangeVerificationObserver verificationRejected()
    ChangeVerificationObservation.Observed(
        AddDeclarationVerificationEvidence(
            request.applied.source,
            source.content,
            relationEvidence,
            listOf(diagnostic),
            semanticEvidence.delta,
        ),
    )
}

private data class HostedMutationBudgets(
    val relation: RelationBudget,
    val traversal: TraversalBudget,
)

private fun hostedMutationBudgets(): HostedMutationBudgets? {
    val records = ResultLimit.parse(256).valueOrNull() ?: return null
    val work = WorkUnitLimit.parse(100_000L).valueOrNull() ?: return null
    val elapsed = ElapsedTimeLimitMillis.parse(30_000L).valueOrNull() ?: return null
    val relationBytes = RelationByteLimit.parse(4_194_304L).valueOrNull() ?: return null
    val relation = RelationBudget(ResourceBudget(records, work, elapsed), relationBytes)
    val traversalBytes = TraversalByteLimit.parse(4_194_304L).valueOrNull() ?: return null
    val depth = TraversalDepthLimit.parse(1).valueOrNull() ?: return null
    val frontier = TraversalFrontierLimit.parse(256).valueOrNull() ?: return null
    val hopTime = ElapsedTimeLimitMillis.parse(1_000L).valueOrNull() ?: return null
    return HostedMutationBudgets(
        relation,
        TraversalBudget(
            records,
            traversalBytes,
            work,
            elapsed,
            depth,
            frontier,
            RelationBudget(ResourceBudget(records, work, hopTime), relationBytes),
        ),
    )
}

private fun rejected(failure: HostedMutationAdmissionFailure) =
    HostedChangePlanningResult.Rejected(failure)

private fun verificationRejected(
    failure: ChangeVerificationObservationRejection =
        ChangeVerificationObservationRejection.COMPILER_OBSERVATION_REJECTED,
) = ChangeVerificationObservation.Rejected(failure)

private fun <Value> awaitHostedRead(block: suspend () -> Value): Value? {
    val completion = CountDownLatch(1)
    val outcome = AtomicReference<Result<Value>?>()
    return try {
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Value>) {
                    outcome.set(result)
                    completion.countDown()
                }
            },
        )
        if (!completion.await(30, TimeUnit.SECONDS)) return null
        outcome.get()?.getOrNull()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
