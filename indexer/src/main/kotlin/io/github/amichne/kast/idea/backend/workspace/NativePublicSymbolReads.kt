@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.workspace

import com.intellij.openapi.project.Project
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.selector.SelectorHandle
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.selectorOperationFamilies
import io.github.amichne.kast.api.contract.selector.toExactSelector
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticGate
import io.github.amichne.kast.idea.workspace.gradle.toWorkspaceSearchScopeModel
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.server.NativePublicSymbolReadFailure
import io.github.amichne.kast.server.NativePublicSymbolReadResult
import io.github.amichne.kast.server.NativePublicSymbolReader
import io.github.amichne.kast.server.PublicSymbolReadBinding
import io.github.amichne.kast.server.PublicSymbolReadMatch
import io.github.amichne.kast.server.PublicSymbolReadProjection
import io.github.amichne.kast.server.PublicSymbolReadQuery
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toSymbolModel
import io.github.amichne.kast.symbol.contract.NativeDetachedDefinition
import io.github.amichne.kast.symbol.contract.NativeProjectionByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.intellij.IntellijDetachedDefinitionProjection
import io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadAdapter
import io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadResult
import io.github.amichne.kast.symbol.intellij.IntellijNativeDefinitionProjector
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtNamedDeclaration

private const val NATIVE_SYMBOL_WORK_LIMIT = 100_000L
private const val NATIVE_SYMBOL_BYTE_LIMIT = 1_048_576L

internal data class DetachedKastSymbol(
    val symbol: Symbol,
    val selectorHandle: SelectorHandle,
) : NativeDetachedDefinition

internal class IdeaNativePublicSymbolReader(
    private val project: Project,
    private val workspaceRoot: NormalizedPath,
    private val semanticGate: WorkspaceSemanticGate,
    private val selectorHandles: SelectorHandleAuthority,
    private val workspaceModelReader: () -> IdeaGradleProjectLoadBridge.GradleWorkspaceModel,
    private val readDispatcher: CoroutineDispatcher,
    private val elapsedLimitMillis: Long,
    private val clock: () -> Long = System::nanoTime,
) : NativePublicSymbolReader {
    /**
     * Proof transition:
     * PublicSymbolReadQuery to NativePublicSymbolReadResult.
     *
     * A completed result establishes one liveness- and freshness-admitted generation, model-owned
     * IntelliJ scope, native discovery, exact-selector revalidation, detached Kotlin definition,
     * and explicit bounded-work evidence. [NativePublicSymbolReadFailure] is the closed expected
     * failure. Raw project model and live IntelliJ values remain inside this physical adapter.
     */
    override suspend fun read(query: PublicSymbolReadQuery): NativePublicSymbolReadResult {
        if (query.workspaceRoot != workspaceRoot) {
            return NativePublicSymbolReadResult.Rejected(
                NativePublicSymbolReadFailure.WORKSPACE_ROOT_MISMATCH,
            )
        }
        val admissionStartedAt = clock()
        return try {
            semanticGate.current { lease ->
                val admissionNanoseconds = elapsedSince(admissionStartedAt)
                if (lease.workspaceRoot.value != workspaceRoot.value) {
                    return@current NativePublicSymbolReadResult.Rejected(
                        NativePublicSymbolReadFailure.WORKSPACE_ROOT_MISMATCH,
                    )
                }
                val request = when (val parsed = query.toDiscoveryRequest(lease, elapsedLimitMillis)) {
                    is NativePublicSymbolRequestParsing.Parsed -> parsed.request
                    NativePublicSymbolRequestParsing.Rejected ->
                        return@current NativePublicSymbolReadResult.Rejected(
                            NativePublicSymbolReadFailure.NATIVE_READ_UNAVAILABLE,
                        )
                }
                val modelCompilation =
                    workspaceModelReader().toWorkspaceSearchScopeModel(lease.workspaceRoot)
                if (modelCompilation is WorkspaceSearchScopeModelCompilation.Rejected) {
                    return@current NativePublicSymbolReadResult.Rejected(
                        NativePublicSymbolReadFailure.PROJECT_MODEL_UNAVAILABLE,
                    )
                }
                val native: IntellijFastSymbolReadResult<DetachedKastSymbol> =
                    withContext(readDispatcher) {
                        IntellijFastSymbolReadAdapter<DetachedKastSymbol>().read(
                            project = project,
                            request = request,
                            modelCompilation = modelCompilation,
                            projector = projector(query),
                        )
                    }
                when (native) {
                    is IntellijFastSymbolReadResult.Rejected ->
                        NativePublicSymbolReadResult.Rejected(
                            NativePublicSymbolReadFailure.NATIVE_READ_UNAVAILABLE,
                        )
                    is IntellijFastSymbolReadResult.Completed<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val completed =
                            native as IntellijFastSymbolReadResult.Completed<DetachedKastSymbol>
                        NativePublicSymbolReadResult.Completed(
                            definitions = completed.definitions.map { definition ->
                                NativePublicSymbolReadResult.Definition(
                                    symbol = definition.symbol,
                                    selectorHandle = definition.selectorHandle,
                                )
                            },
                            evidence = completed.toEvidence(
                                generation = lease.generation.value,
                                admissionNanoseconds = admissionNanoseconds,
                            ),
                        )
                    }
                }
            }
        } catch (_: ConflictException) {
            NativePublicSymbolReadResult.Rejected(
                NativePublicSymbolReadFailure.RUNTIME_OR_SEMANTIC_UNAVAILABLE,
            )
        }
    }

    private fun projector(
        query: PublicSymbolReadQuery,
    ): IntellijNativeDefinitionProjector<DetachedKastSymbol> =
        IntellijNativeDefinitionProjector { declaration, _ ->
            val kotlinDeclaration = declaration as? KtNamedDeclaration
                                    ?: return@IntellijNativeDefinitionProjector Refinement.Rejected(
                                        io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadRejection
                                            .PROJECTION_REJECTED,
                                    )
            val symbol = analyze(kotlinDeclaration) {
                kotlinDeclaration.toSymbolModel(
                    containingDeclaration = compilerContainingDeclarationName(kotlinDeclaration),
                    includeDeclarationScope =
                        query.projection.includesDeclarationScope(),
                    includeDocumentation = query.projection.includesDocumentation(),
                )
            }
            val encodedBytes = Json.encodeToString(Symbol.serializer(), symbol)
                .toByteArray(StandardCharsets.UTF_8)
                .size
                .toLong()
            when (val parsed = NativeProjectionByteCount.parse(encodedBytes)) {
                is Refinement.Refined -> when (
                    val issued = selectorHandles.issue(
                        symbol.toExactSelector(),
                        symbol.kind.selectorOperationFamilies(),
                    )
                ) {
                    is SelectorHandleAuthority.IssueResult.Issued -> Refinement.Refined(
                        IntellijDetachedDefinitionProjection(
                            definition = DetachedKastSymbol(symbol, issued.handle),
                            encodedBytes = parsed.value,
                        ),
                    )
                    SelectorHandleAuthority.IssueResult.Unavailable -> Refinement.Rejected(
                        io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadRejection
                            .PROJECTION_REJECTED,
                    )
                }
                is Refinement.Rejected -> Refinement.Rejected(
                    io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadRejection
                        .INTERNAL_INVARIANT,
                )
            }
        }

    private fun elapsedSince(startedAt: Long): Long =
        (clock() - startedAt).coerceAtLeast(0L)
}

internal fun KastIndexerBackend.nativePublicSymbolReader(): NativePublicSymbolReader =
    IdeaNativePublicSymbolReader(
        project = project,
        workspaceRoot = NormalizedPath.of(workspaceRoot),
        semanticGate = workspaceSemanticGate,
        selectorHandles = selectorHandles,
        workspaceModelReader = workspaceModelReader,
        readDispatcher = readDispatcher,
        elapsedLimitMillis = limits.requestTimeoutMillis,
    )

internal fun KastIndexerBackend.nativePublicSymbolBinding(): PublicSymbolReadBinding.Native =
    PublicSymbolReadBinding.Native(
        workspaceRoot = NormalizedPath.of(workspaceRoot),
        selectorHandles = selectorHandles,
        reader = nativePublicSymbolReader(),
    )

private sealed interface NativePublicSymbolRequestParsing {
    data class Parsed(
        val request: SymbolDiscoveryRequest,
    ) : NativePublicSymbolRequestParsing

    data object Rejected : NativePublicSymbolRequestParsing
}

/**
 * Proof transition:
 * PublicSymbolReadQuery + SemanticReadLease + Long to NativePublicSymbolRequestParsing.
 *
 * A parsed result establishes exact generation-bound workspace scope plus positive result, work,
 * elapsed-time, and byte budgets. NativePublicSymbolRequestParsing.Rejected is the closed expected
 * failure. Raw public primitives may be extracted only at this physical request boundary.
 */
private fun PublicSymbolReadQuery.toDiscoveryRequest(
    lease: io.github.amichne.kast.workspace.contract.SemanticReadLease,
    elapsedLimitMillis: Long,
): NativePublicSymbolRequestParsing {
    val pattern = when (
        val parsed = SymbolDiscoveryPattern.parse(
            pattern.value
                .split('.')
                .last()
                .removeSurrounding("`"),
        )
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return NativePublicSymbolRequestParsing.Rejected
    }
    val resultLimit = when (val parsed = ResultLimit.parse(maxResults.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return NativePublicSymbolRequestParsing.Rejected
    }
    val workLimit = when (val parsed = WorkUnitLimit.parse(NATIVE_SYMBOL_WORK_LIMIT)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return NativePublicSymbolRequestParsing.Rejected
    }
    val timeLimit = when (val parsed = ElapsedTimeLimitMillis.parse(elapsedLimitMillis)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return NativePublicSymbolRequestParsing.Rejected
    }
    val byteLimit = when (
        val parsed =
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit.parse(
                NATIVE_SYMBOL_BYTE_LIMIT,
            )
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return NativePublicSymbolRequestParsing.Rejected
    }
    return NativePublicSymbolRequestParsing.Parsed(
        SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(
                lease = lease,
                scope = SymbolSearchScope.Workspace(
                    sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                    libraries = SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            kind = kind.toDiscoveryKind(),
            pattern = pattern,
            budget = SymbolDiscoveryBudget(
                resources = ResourceBudget(resultLimit, workLimit, timeLimit),
                returnedBytes = byteLimit,
            ),
            match = when (match) {
                PublicSymbolReadMatch.FUZZY -> SymbolDiscoveryMatch.FUZZY
                PublicSymbolReadMatch.EXACT_NAME -> SymbolDiscoveryMatch.EXACT_NAME
            },
        ),
    )
}

private fun SymbolKind?.toDiscoveryKind(): SymbolDiscoveryKind = when (this) {
    SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT -> SymbolDiscoveryKind.CLASS
    else -> SymbolDiscoveryKind.SYMBOL
}

private fun PublicSymbolReadProjection.includesDeclarationScope(): Boolean =
    this == PublicSymbolReadProjection.DECLARATION_SCOPE ||
    this == PublicSymbolReadProjection.DECLARATION_SCOPE_AND_DOCUMENTATION

private fun PublicSymbolReadProjection.includesDocumentation(): Boolean =
    this == PublicSymbolReadProjection.DOCUMENTATION ||
    this == PublicSymbolReadProjection.DECLARATION_SCOPE_AND_DOCUMENTATION
