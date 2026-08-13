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
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
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
     * [IntellijSearchScopeFailure], [IntellijNativeDiscoveryRejection], and
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
                IntellijNativeDiscoveryQuery(
                    environmentState = { project.discoveryEnvironmentState() },
                    cancellationCheck = ProgressManager::checkCanceled,
                ).discover(
                    compiledScope = compiledScope,
                    request = request,
                    contributors = request.kind.nativeContributors(),
                )
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

internal fun SymbolDiscoveryKind.nativeContributors(): List<ChooseByNameContributor> = when (this) {
    SymbolDiscoveryKind.FILE -> ChooseByNameContributor.FILE_EP_NAME.extensionList
    SymbolDiscoveryKind.CLASS -> ChooseByNameContributor.CLASS_EP_NAME.extensionList
    SymbolDiscoveryKind.SYMBOL -> ChooseByNameRegistry.getInstance().symbolModelContributors
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
    ): IntellijFastSymbolReadResult<Definition> = readAction {
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
            )
            when (
                val execution = query.discover(
                    compiledScope = compiledScope,
                    request = request,
                    contributors = request.kind.nativeContributors()
                        .filter(request.kind::isKotlinDeclarationContributor),
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
                    batch.candidates.indices.forEach { ordinal ->
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
                                return@forEach
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
                                return@forEach
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
                        val projectionStartedAt = clock.now()
                        when (val projected = projector.project(live.declaration, proof)) {
                            is Refinement.Refined -> {
                                definitions += projected.value.definition
                                projectionBytes =
                                    saturatedAdd(projectionBytes, projected.value.encodedBytes.value)
                            }
                            is Refinement.Rejected ->
                                return@execute IntellijFastSymbolReadResult.Rejected(
                                    projected.failure,
                                )
                        }
                        definitionProjectionNanoseconds += elapsedSince(projectionStartedAt)
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
}

private fun SymbolDiscoveryKind.isKotlinDeclarationContributor(
    contributor: ChooseByNameContributor,
): Boolean = contributor.javaClass.name in when (this) {
    SymbolDiscoveryKind.CLASS -> setOf(
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassContributor",
    )
    SymbolDiscoveryKind.SYMBOL -> setOf(
        "org.jetbrains.kotlin.idea.goto.KotlinGotoClassSymbolContributor",
        "org.jetbrains.kotlin.idea.goto.KotlinGotoFunctionSymbolContributor",
        "org.jetbrains.kotlin.idea.goto.KotlinGotoPropertySymbolContributor",
    )
    SymbolDiscoveryKind.FILE -> emptySet()
}

private fun SymbolDiscoveryOutcome.batch(): SymbolDiscoveryBatch = when (this) {
    is SymbolDiscoveryOutcome.Complete -> batch
    is SymbolDiscoveryOutcome.Qualified -> batch
}

private fun saturatedAdd(
    left: Long,
    right: Long,
): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
