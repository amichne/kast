package io.github.amichne.kast.runtime.composition.change

import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.domain
import io.github.amichne.kast.change.verify.AddDeclarationVerificationEvidence
import io.github.amichne.kast.change.verify.ChangeVerificationObservation
import io.github.amichne.kast.change.verify.ChangeVerificationObservationRejection
import io.github.amichne.kast.change.verify.ChangeVerificationObservationRequest
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.change.verify.ObservedAddDeclarationDelta
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.runtime.composition.InstalledSemanticBudgets
import io.github.amichne.kast.runtime.composition.SemanticRuntimePorts
import io.github.amichne.kast.runtime.composition.installedSemanticBudgets
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolCompilation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Result-generation observer backed only by the installed request-local compiler ports. */
internal class InstalledChangeVerificationObserver(
    private val workspace: WorkspaceInspectionOperations,
    private val semantic: SemanticRuntimePorts,
    private val sources: AddDeclarationSourceObserver,
) : ChangeVerificationObserver {
    /**
     * Proof transition: `ChangeVerificationObservationRequest -> ChangeVerificationObservation`.
     *
     * Observed AddDeclaration evidence carries the exact physical postimage, a freshly discovered
     * G1 selector for the original target, complete G1 relation and diagnostic batches, and a
     * separately compiler-resolved declaration delta. Moved generations, asynchronous compiler
     * effects, incomplete evidence, and unsupported intent families fail closed.
     */
    override fun observe(
        request: ChangeVerificationObservationRequest,
    ): ChangeVerificationObservation {
        val plan = request.plan as? AddDeclarationChangePlan
                   ?: return rejected()
        val published = (workspace.inspect() as? WorkspaceRuntimeState.Ready)?.workspace
                        ?: return rejected(ChangeVerificationObservationRejection.RESULTING_GENERATION_MOVED)
        if (!published.samePublication(request.resulting.workspace)) {
            return rejected(ChangeVerificationObservationRejection.RESULTING_GENERATION_MOVED)
        }
        val observedSource = when (val result = sources.observe(request.applied.source)) {
            is SourceObservationResult.Observed -> result.source as? ObservedMutationSource
                                                   ?: return rejected()
            is SourceObservationResult.Rejected -> return rejected()
        }
        val budgets = installedSemanticBudgets() ?: return rejected()
        val original = resolveUnique(
            published,
            plan.target.file.path.value,
            plan.target.selector.name.value,
            plan.target.selector.scope,
            budgets,
        ) ?: return rejected()
        when (original.matchDeclarationAcrossGeneration(plan.target.selector)) {
            InstalledPriorDeclarationMatch.Matched -> Unit
            InstalledPriorDeclarationMatch.MovedOrChanged ->
                return rejected()
        }
        val relations = plan.evidence.relations.map { planned ->
            val compilation = when (val read = awaitCompilerRead {
                semantic.relation.read(
                    RelationRequest.start(
                        original,
                        planned.meaning.domain(),
                        budgets.relation,
                    ),
                )
            }) {
                is InstalledCompilerRead.Completed ->
                    read.value as? RelationCompilation.Complete
                    ?: return rejected()
                is InstalledCompilerRead.Rejected -> return rejected()
            }
            RelationReadResult.Complete(compilation.batch, compilation.coverage)
        }
        val scope = when (val admitted = DiagnosticScope.fromCanonicalPaths(
            published.readLease,
            listOf(Path.of(request.applied.source.path.value)),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected()
        }
        val diagnosticCompilation = when (val read = awaitCompilerRead {
            semantic.diagnostic.check(scope)
        }) {
            is InstalledCompilerRead.Completed ->
                read.value as? DiagnosticCompilation.Complete
                ?: return rejected()
            is InstalledCompilerRead.Rejected -> return rejected()
        }
        val diagnostic = DiagnosticCheckResult.Complete(
            diagnosticCompilation.batch,
            diagnosticCompilation.coverage,
        )
        val added = resolveUnique(
            published,
            plan.target.file.path.value,
            plan.expectedSemanticDelta.declarationName,
            plan.target.selector.scope,
            budgets,
        ) ?: return rejected()
        val identity = when (val observed = added.observeAddedIdentity(
            original,
            plan.expectedSemanticDelta.packageName,
        )) {
            is InstalledObservedDeclarationIdentityObservation.Observed -> observed.identity
            InstalledObservedDeclarationIdentityObservation.Rejected ->
                return rejected()
        }
        val delta = when (val admitted = ObservedAddDeclarationDelta.fromCompilerBoundary(
            identity.packageName,
            added.name.value,
            identity.kind,
            1,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected()
        }
        return ChangeVerificationObservation.Observed(
            AddDeclarationVerificationEvidence(
                request.applied.source,
                observedSource.content,
                relations,
                listOf(diagnostic),
                delta,
            ),
        )
    }

    private fun resolveUnique(
        published: PublishedWorkspace,
        source: String,
        name: String,
        scope: SymbolSearchScope,
        budgets: InstalledSemanticBudgets,
    ): SymbolSelector? {
        val file = when (val admitted = CanonicalWorkspaceFilePath.fromCanonicalPath(
            published.root,
            Path.of(source),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
        val pattern = when (val parsed = SymbolDiscoveryPattern.parse(name)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
        val request = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                published.readLease,
                scope,
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                pattern,
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            budgets.discovery,
        )
        val compilation = when (val read = awaitCompilerRead {
            semantic.symbolDiscovery.compile(request)
        }) {
            is InstalledCompilerRead.Completed ->
                read.value as? SymbolCompilation.Compiled ?: return null
            is InstalledCompilerRead.Rejected -> return null
        }
        val outcome = compilation.outcome
        val batch = (outcome as? SymbolDiscoveryOutcome.Complete)?.batch ?: return null
        val candidates = batch.candidates.withIndex().filter { indexed ->
            val candidate = indexed.value
            candidate.name.value == name &&
            candidate.location is SymbolDiscoveryCandidateLocation.Declaration &&
            candidate.location.file.stableValue == file.value
        }
        val candidate = candidates.singleOrNull() ?: return null
        val selection = when (val selected = SymbolDiscoverySelection.select(
            batch,
            candidate.index,
        )) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return null
        }
        return when (val read = awaitCompilerRead {
            semantic.symbolExact.resolve(SymbolResolutionRequest(selection))
        }) {
            is InstalledCompilerRead.Completed -> when (val resolved = read.value) {
                is SymbolResolutionCompilation.Resolved -> resolved.selector
                is SymbolResolutionCompilation.Rejected -> null
            }
            is InstalledCompilerRead.Rejected -> null
        }
    }
}

private fun PublishedWorkspace.samePublication(other: PublishedWorkspace): Boolean =
    readLease == other.readLease && sourceState == other.sourceState && sourceRoots == other.sourceRoots

private enum class InstalledCompilerReadFailure {
    INTERRUPTED,
    FAILED,
}

private sealed interface InstalledCompilerRead<out Value> {
    data class Completed<Value>(
        val value: Value,
    ) : InstalledCompilerRead<Value>

    data class Rejected(
        val failure: InstalledCompilerReadFailure,
    ) : InstalledCompilerRead<Nothing>
}

/**
 * Proof transition: `suspend () -> Value -> InstalledCompilerRead<Value>`.
 *
 * Completed establishes that the compiler read reached its terminal result even when IntelliJ
 * suspended it behind write priority. [InstalledCompilerRead.Rejected] closes interruption and
 * unexpected coroutine failure. Live compiler values remain inside the calling observation.
 */
private fun <Value> awaitCompilerRead(
    block: suspend () -> Value,
): InstalledCompilerRead<Value> {
    val completion = CountDownLatch(1)
    val resultReference = AtomicReference<Result<Value>?>()
    try {
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Value>) {
                    resultReference.set(result)
                    completion.countDown()
                }
            },
        )
        completion.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return InstalledCompilerRead.Rejected(InstalledCompilerReadFailure.INTERRUPTED)
    } catch (_: Exception) {
        return InstalledCompilerRead.Rejected(InstalledCompilerReadFailure.FAILED)
    }
    return resultReference.get()?.fold(
        onSuccess = { value -> InstalledCompilerRead.Completed(value) },
        onFailure = { InstalledCompilerRead.Rejected(InstalledCompilerReadFailure.FAILED) },
    ) ?: InstalledCompilerRead.Rejected(InstalledCompilerReadFailure.FAILED)
}

private fun rejected(
    reason: ChangeVerificationObservationRejection =
        ChangeVerificationObservationRejection.COMPILER_OBSERVATION_REJECTED,
): ChangeVerificationObservation.Rejected = ChangeVerificationObservation.Rejected(reason)
