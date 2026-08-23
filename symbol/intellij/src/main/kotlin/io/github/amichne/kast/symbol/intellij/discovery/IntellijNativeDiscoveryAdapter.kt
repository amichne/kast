package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameRegistry
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.NativeDetachedDefinition
import io.github.amichne.kast.symbol.contract.NativeProjectionByteCount
import io.github.amichne.kast.symbol.contract.RevalidatedExactDeclaration
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation

internal sealed interface IntellijNativeDiscoveryResult {
    data class Discovered(
        val outcome: SymbolDiscoveryOutcome,
    ) : IntellijNativeDiscoveryResult

    data class Rejected(
        val reason: IntellijNativeDiscoveryRejection,
    ) : IntellijNativeDiscoveryResult

    data class ScopeRejected(
        val failures: Set<IntellijSearchScopeFailure>,
    ) : IntellijNativeDiscoveryResult
}

internal class IntellijNativeDiscoveryAdapter(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
) {
    /**
     * Proof transition:
     * Project + SymbolDiscoveryRequest + WorkspaceSearchScopeModelCompilation to
     * IntellijNativeDiscoveryResult.
     *
     * Establishes a write-priority cancellable IntelliJ read whose Choose-by-Name provider work can
     * begin only after KIP-012 compiles exact model ownership into a generation-bound native scope.
     * File contributors remain scoped discovery providers; class and symbol contributors are limited
     * to Kotlin declarations that can refine into the product's K2 exact selector. [IntellijSearchScopeFailure], [IntellijNativeDiscoveryRejection], and
     * [io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification] are the closed expected
     * failure and partial-coverage states. Cancellation propagates through [readAction]. The live
     * project, providers, PSI, files, and scope remain inside the restarted request-local read.
     */
    suspend fun discover(
        project: Project,
        request: SymbolDiscoveryRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
    ): IntellijNativeDiscoveryResult = readAction {
        when (
            val scoped = scopeQuery.execute(
                project = project,
                request = request.scope,
                modelCompilation = modelCompilation,
            ) { compiledScope ->
                when (val target = request.target) {
                    is SymbolDiscoveryTarget.Name -> IntellijNativeDiscoveryQuery(
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = ProgressManager::checkCanceled,
                    ).discover(
                        compiledScope = compiledScope,
                        request = request,
                        contributors = target.kind.nativeContributors()
                            .filter(target.kind::isAdmittedContributor),
                    )
                    is SymbolDiscoveryTarget.Location,
                    is SymbolDiscoveryTarget.Structure,
                    is SymbolDiscoveryTarget.Text,
                        -> IntellijSupplementalDiscoveryQuery(
                        project = project,
                        environmentState = { project.discoveryEnvironmentState() },
                    ).discover(compiledScope, request)
                }
            }
        ) {
            is IntellijScopedQueryResult.Completed -> when (val execution = scoped.value) {
                is IntellijNativeDiscoveryExecution.Produced ->
                    IntellijNativeDiscoveryResult.Discovered(execution.outcome)
                is IntellijNativeDiscoveryExecution.Rejected ->
                    IntellijNativeDiscoveryResult.Rejected(execution.reason)
            }
            is IntellijScopedQueryResult.Rejected ->
                IntellijNativeDiscoveryResult.ScopeRejected(scoped.failures)
        }
    }
}

/**
 * Proof transition: Project to IntellijDiscoveryEnvironmentState.
 *
 * Establishes the closed request-local READY, DUMB, or DISPOSED state before and during a native
 * symbol read. The live [Project] may be extracted only inside the restartable IntelliJ read.
 */
internal fun Project.discoveryEnvironmentState(): IntellijDiscoveryEnvironmentState = when {
    isDisposed -> IntellijDiscoveryEnvironmentState.DISPOSED
    DumbService.getInstance(this).isDumb -> IntellijDiscoveryEnvironmentState.DUMB
    else -> IntellijDiscoveryEnvironmentState.READY
}

internal fun SymbolNameDiscoveryKind.nativeContributors(): List<ChooseByNameContributor> = when (this) {
    SymbolNameDiscoveryKind.FILE -> ChooseByNameContributor.FILE_EP_NAME.extensionList
    SymbolNameDiscoveryKind.CLASS -> ChooseByNameContributor.CLASS_EP_NAME.extensionList
    SymbolNameDiscoveryKind.SYMBOL -> ChooseByNameRegistry.getInstance().symbolModelContributors
}

enum class IntellijFastSymbolReadRejection {
    SCOPE_REJECTED,
    DISCOVERY_REJECTED,
    EXACT_DEFINITION_UNAVAILABLE,
    PROJECTION_REJECTED,
    INTERNAL_INVARIANT,
}

data class IntellijDetachedDefinitionProjection<out Definition : NativeDetachedDefinition>(
    val definition: Definition,
    val encodedBytes: NativeProjectionByteCount,
)

fun interface IntellijNativeDefinitionProjector<Definition : NativeDetachedDefinition> {
    /**
     * Proof transition:
     * live PsiNamedElement + RevalidatedExactDeclaration to
     * Refinement<IntellijDetachedDefinitionProjection<Definition>, IntellijFastSymbolReadRejection>.
     *
     * A refined projection establishes that the definition is detached and byte-measured under the
     * exact selector proof. [IntellijFastSymbolReadRejection] is the closed expected failure. The
     * live declaration may be consumed only inside this request-local callback.
     */
    fun project(
        declaration: PsiNamedElement,
        proof: RevalidatedExactDeclaration,
    ): Refinement<
        IntellijDetachedDefinitionProjection<Definition>,
        IntellijFastSymbolReadRejection,
        >
}

data class IntellijFastSymbolReadMetrics(
    val scopeCompilationNanoseconds: Long,
    val semanticResolutionNanoseconds: Long,
    val definitionProjectionNanoseconds: Long,
    val projectionBytes: NativeProjectionByteCount,
)

sealed interface IntellijFastSymbolReadResult<out Definition : NativeDetachedDefinition> {
    data class Completed<out Definition : NativeDetachedDefinition>(
        val definitions: List<Definition>,
        val discovery: SymbolDiscoveryOutcome,
        val extraQualifications: Set<SymbolDiscoveryQualification>,
        val metrics: IntellijFastSymbolReadMetrics,
    ) : IntellijFastSymbolReadResult<Definition>

    data class Rejected(
        val reason: IntellijFastSymbolReadRejection,
    ) : IntellijFastSymbolReadResult<Nothing>
}

class IntellijFastSymbolReadAdapter<Definition : NativeDetachedDefinition> private constructor(
    private val scopeQuery: IntellijSearchScopeQueryAdapter = IntellijSearchScopeQueryAdapter(),
    private val clock: IntellijDiscoveryNanoClock = SystemIntellijDiscoveryNanoClock,
) {
    constructor() : this(IntellijSearchScopeQueryAdapter(), SystemIntellijDiscoveryNanoClock)

    constructor(clock: IntellijReadNanoClock) : this(IntellijSearchScopeQueryAdapter(), clock)

    /**
     * Proof transition:
     * Project + SymbolDiscoveryRequest + WorkspaceSearchScopeModelCompilation +
     * IntellijNativeDefinitionProjector to IntellijFastSymbolReadResult.
     *
     * A completed result establishes bounded native discovery, exact selector issue and
     * revalidation, and detached definition projection in one cancellable IntelliJ read action.
     * [IntellijFastSymbolReadRejection], [IntellijSearchScopeFailure], and
     * [SymbolDiscoveryQualification] close expected failure and partial coverage. Live project,
     * provider, scope, VFS, and PSI values never leave the read action.
     */
    suspend fun read(
        project: Project,
        request: SymbolDiscoveryRequest,
        modelCompilation: WorkspaceSearchScopeModelCompilation,
        projector: IntellijNativeDefinitionProjector<Definition>,
        itemAdmission: IntellijDiscoveryItemAdmissionPolicy =
            AdmitEveryIntellijDiscoveryItem,
    ): IntellijFastSymbolReadResult<Definition> = readAction {
        val target = request.target as? SymbolDiscoveryTarget.Name
                     ?: return@readAction IntellijFastSymbolReadResult.Rejected(
                         IntellijFastSymbolReadRejection.DISCOVERY_REJECTED,
                     )
        var scopeCompilationNanoseconds = 0L
        val scopeStartedAt = clock.now()
        val scoped = scopeQuery.execute(
            project = project,
            request = request.scope,
            modelCompilation = modelCompilation,
        ) { compiledScope ->
            scopeCompilationNanoseconds = elapsedSince(scopeStartedAt)
            val query = IntellijNativeDiscoveryQuery(
                environmentState = { project.discoveryEnvironmentState() },
                cancellationCheck = ProgressManager::checkCanceled,
                clock = clock,
                itemAdmission = itemAdmission,
            )
            when (
                val execution = query.discover(
                    compiledScope = compiledScope,
                    request = request,
                    contributors = target.kind.nativeContributors()
                        .filter(target.kind::isAdmittedContributor),
                )
            ) {
                is IntellijNativeDiscoveryExecution.Rejected ->
                    IntellijFastSymbolReadResult.Rejected(
                        IntellijFastSymbolReadRejection.DISCOVERY_REJECTED,
                    )
                is IntellijNativeDiscoveryExecution.Produced -> {
                    val batch = execution.outcome.batch()
                    val lookup = IntellijPsiExactDeclarationLookup(project)
                    val exactQuery = IntellijExactSelectorQuery(
                        lookup = lookup,
                        environmentState = { project.discoveryEnvironmentState() },
                        cancellationCheck = ProgressManager::checkCanceled,
                    )
                    val definitions = mutableListOf<Definition>()
                    val extraQualifications = linkedSetOf<SymbolDiscoveryQualification>()
                    var semanticNanoseconds = 0L
                    var definitionProjectionNanoseconds = 0L
                    var projectionBytes = 0L
                    for (ordinal in batch.candidates.indices) {
                        if (request.timeLimitReached(scopeStartedAt)) {
                            extraQualifications += SymbolDiscoveryQualification.TIME_LIMIT_REACHED
                            break
                        }
                        val semanticStartedAt = clock.now()
                        val selection = when (
                            val selected = SymbolDiscoverySelection.select(batch, ordinal)
                        ) {
                            is Refinement.Refined -> selected.value
                            is Refinement.Rejected ->
                                return@execute IntellijFastSymbolReadResult.Rejected(
                                    IntellijFastSymbolReadRejection.INTERNAL_INVARIANT,
                                )
                        }
                        val selector = when (
                            val resolved = exactQuery.resolve(compiledScope, selection)
                        ) {
                            is IntellijExactSelectorResolution.Resolved -> resolved.selector
                            is IntellijExactSelectorResolution.Rejected -> {
                                extraQualifications +=
                                    SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE
                                semanticNanoseconds += elapsedSince(semanticStartedAt)
                                continue
                            }
                            is IntellijExactSelectorResolution.ScopeRejected ->
                                return@execute IntellijFastSymbolReadResult.Rejected(
                                    IntellijFastSymbolReadRejection.SCOPE_REJECTED,
                                )
                        }
                        val live = when (
                            val found = lookup.findLive(compiledScope, selector.lookupKey())
                        ) {
                            is IntellijLiveExactDeclarationLookupResult.Found -> found
                            is IntellijLiveExactDeclarationLookupResult.Rejected -> {
                                extraQualifications +=
                                    SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE
                                semanticNanoseconds += elapsedSince(semanticStartedAt)
                                continue
                            }
                        }
                        val proof = when (
                            val revalidated =
                                RevalidatedExactDeclaration.validate(selector, live.evidence)
                        ) {
                            is Refinement.Refined -> revalidated.value
                            is Refinement.Rejected ->
                                return@execute IntellijFastSymbolReadResult.Rejected(
                                    IntellijFastSymbolReadRejection.EXACT_DEFINITION_UNAVAILABLE,
                                )
                        }
                        semanticNanoseconds += elapsedSince(semanticStartedAt)
                        if (request.timeLimitReached(scopeStartedAt)) {
                            extraQualifications += SymbolDiscoveryQualification.TIME_LIMIT_REACHED
                            break
                        }
                        val projectionStartedAt = clock.now()
                        when (val projected = projector.project(live.declaration, proof)) {
                            is Refinement.Refined -> {
                                definitionProjectionNanoseconds +=
                                    elapsedSince(projectionStartedAt)
                                if (request.timeLimitReached(scopeStartedAt)) {
                                    extraQualifications +=
                                        SymbolDiscoveryQualification.TIME_LIMIT_REACHED
                                    break
                                }
                                if (
                                    projected.value.encodedBytes.value >
                                    request.budget.returnedBytes.value - projectionBytes
                                ) {
                                    extraQualifications +=
                                        SymbolDiscoveryQualification.BYTE_LIMIT_REACHED
                                    break
                                }
                                definitions += projected.value.definition
                                projectionBytes =
                                    saturatedAdd(projectionBytes, projected.value.encodedBytes.value)
                            }
                            is Refinement.Rejected ->
                                return@execute IntellijFastSymbolReadResult.Rejected(
                                    projected.failure,
                                )
                        }
                    }
                    val measuredBytes = when (
                        val parsed = NativeProjectionByteCount.parse(projectionBytes)
                    ) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return@execute IntellijFastSymbolReadResult.Rejected(
                                IntellijFastSymbolReadRejection.INTERNAL_INVARIANT,
                            )
                    }
                    IntellijFastSymbolReadResult.Completed(
                        definitions = definitions.toList(),
                        discovery = execution.outcome,
                        extraQualifications = extraQualifications,
                        metrics = IntellijFastSymbolReadMetrics(
                            scopeCompilationNanoseconds = scopeCompilationNanoseconds,
                            semanticResolutionNanoseconds = semanticNanoseconds,
                            definitionProjectionNanoseconds =
                                definitionProjectionNanoseconds,
                            projectionBytes = measuredBytes,
                        ),
                    )
                }
            }
        }
        when (scoped) {
            is IntellijScopedQueryResult.Completed -> scoped.value
            is IntellijScopedQueryResult.Rejected ->
                IntellijFastSymbolReadResult.Rejected(
                    IntellijFastSymbolReadRejection.SCOPE_REJECTED,
                )
        }
    }

    private fun elapsedSince(startedAt: Long): Long =
        (clock.now() - startedAt).coerceAtLeast(0L)

    private fun SymbolDiscoveryRequest.timeLimitReached(startedAt: Long): Boolean =
        elapsedSince(startedAt) >= elapsedLimitNanoseconds().value
}

private fun SymbolNameDiscoveryKind.isAdmittedContributor(
    contributor: ChooseByNameContributor,
): Boolean = when (this) {
    SymbolNameDiscoveryKind.FILE -> true
    SymbolNameDiscoveryKind.CLASS -> contributor.javaClass.name in setOf(
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor",
    )
    SymbolNameDiscoveryKind.SYMBOL -> contributor.javaClass.name in setOf(
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor",
        "org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor",
        "org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor",
    )
}

private fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch = when (this) {
    is SymbolDiscoveryOutcome.Complete -> batch
    is SymbolDiscoveryOutcome.Qualified -> batch
}

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
