package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.topology.contract.CompleteTopologyFile
import io.github.amichne.kast.topology.contract.TopologyEdge
import io.github.amichne.kast.topology.contract.TopologyEdgeKind
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologyExtractionRequest
import io.github.amichne.kast.topology.contract.TopologyFileExtraction
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import kotlinx.coroutines.CancellationException
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeReference
import java.nio.file.Path

/** Public native K2 boundary for one exact topology candidate. */
class IntellijTopologyFileExtractor {
    private val registries = TopologyProjectionRegistryCache()

    /**
     * Proof transition: `(Project, PublishedWorkspace, TopologyExtractionRequest) ->
     * TopologyFileExtraction`.
     *
     * Complete output establishes detached compiler symbols and repository-internal edges for the
     * exact admitted file. Candidate PSI is projected once per exact content generation; only the
     * requested file is reloaded and receives terminal output. [TopologyExtractionFailure] is the
     * closed expected failure. Cancellation propagates and live Project, VFS, PSI, and K2 values
     * never leave the read action.
     */
    suspend fun extract(
        project: Project,
        current: PublishedWorkspace,
        request: TopologyExtractionRequest,
    ): TopologyFileExtraction {
        if (
            project.isDisposed || current.readLease != request.candidates.workspace.lease ||
            current.sourceState != request.candidates.workspace.sourceState
        ) {
            return failed(TopologyExtractionFailure.PROJECT_UNAVAILABLE)
        }
        return try {
            readAction {
                if (DumbService.isDumb(project)) {
                    return@readAction failed(TopologyExtractionFailure.COMPILER_UNAVAILABLE)
                }
                extractInReadAction(project, request)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            failed(TopologyExtractionFailure.COMPILER_UNAVAILABLE)
        } catch (_: LinkageError) {
            failed(TopologyExtractionFailure.COMPILER_UNAVAILABLE)
        }
    }

    private fun extractInReadAction(
        project: Project,
        request: TopologyExtractionRequest,
    ): TopologyFileExtraction {
        val registryKey = TopologyProjectionRegistryKey.from(request.candidates)
        val registry = when (
            val resolution = registries.resolve(registryKey) {
                buildRegistry(project, request, registryKey)
            }
        ) {
            is TopologyProjectionRegistryResolution.Ready -> resolution.registry
            is TopologyProjectionRegistryResolution.Rejected -> return failed(resolution.failure)
        }
        val requested = when (val lookup = load(project, request.file)) {
            is TopologyFileLoad.Loaded -> lookup.file
            TopologyFileLoad.Unavailable ->
                return failed(TopologyExtractionFailure.FILE_UNAVAILABLE)
        }
        val symbolByDeclaration = buildMap {
            requested.declarations.forEach { declaration ->
                when (
                    val lookup = registry.symbolAt(
                        requested.file,
                        declaration.textRange.startOffset,
                        declaration.textRange.endOffset,
                    )
                ) {
                    is TopologyRegistrySymbolLookup.Found -> put(declaration, lookup.symbol)
                    TopologyRegistrySymbolLookup.Unavailable -> Unit
                    TopologyRegistrySymbolLookup.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
            }
        }
        val requestedSymbols = requested.declarations.mapNotNull(symbolByDeclaration::get)
            .distinct()
            .sorted()
        val edges = mutableListOf<TopologyEdge>()
        PsiTreeUtil.collectElementsOfType(requested.psi, KtReferenceExpression::class.java)
            .sortedBy { it.textRange.startOffset }
            .forEach { reference ->
                val source = when (val owner = reference.owningSymbol(symbolByDeclaration)) {
                    is OwningTopologySymbol.Found -> owner.symbol
                    OwningTopologySymbol.Unresolved -> return@forEach
                }
                val targetIdentity = when (val resolved = reference.topologyTarget(registry)) {
                    is TopologyReferenceTarget.Found -> resolved.identity
                    TopologyReferenceTarget.Unresolved -> return@forEach
                    TopologyReferenceTarget.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
                val target = when (val lookup = registry.symbol(targetIdentity)) {
                    is TopologyRegistrySymbolLookup.Found -> lookup.symbol
                    TopologyRegistrySymbolLookup.Unavailable -> return@forEach
                    TopologyRegistrySymbolLookup.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
                val kind = reference.edgeKind()
                val occurrence = when (val refined = reference.topologyOccurrence()) {
                    is TopologyReferenceOccurrence.Admitted -> refined.range
                    TopologyReferenceOccurrence.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
                val edge = TopologyEdge.fromBoundary(
                    kind,
                    source,
                    target,
                    occurrence.startOffset,
                    occurrence.endOffset,
                )
                when (edge) {
                    is Refinement.Refined -> edges += edge.value
                    is Refinement.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
            }
        requested.declarations.forEach { declaration ->
            val source = symbolByDeclaration[declaration] ?: return@forEach
            val overrides = when (val projectedOverrides =
                declaration.directOverrideTopologyIdentities(registry)
            ) {
                is TopologyOverrideProjection.Projected -> projectedOverrides.identities
                TopologyOverrideProjection.Rejected ->
                    return failed(TopologyExtractionFailure.FACT_REJECTED)
            }
            overrides.forEach { targetIdentity ->
                val target = when (val lookup = registry.symbol(targetIdentity)) {
                    is TopologyRegistrySymbolLookup.Found -> lookup.symbol
                    TopologyRegistrySymbolLookup.Unavailable -> return@forEach
                    TopologyRegistrySymbolLookup.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
                val range = declaration.nameIdentifier?.textRange ?: declaration.textRange
                when (val edge = TopologyEdge.fromBoundary(
                    TopologyEdgeKind.OVERRIDE,
                    source,
                    target,
                    range.startOffset,
                    range.endOffset,
                )) {
                    is Refinement.Refined -> edges += edge.value
                    is Refinement.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
            }
        }
        return when (
            val complete = CompleteTopologyFile.admit(
                request.file,
                requestedSymbols,
                edges.distinct().sorted(),
            )
        ) {
            is Refinement.Refined -> TopologyFileExtraction.Complete(complete.value)
            is Refinement.Rejected -> failed(TopologyExtractionFailure.FACT_REJECTED)
        }
    }

    /**
     * Proof transition: `(Project, TopologyExtractionRequest,
     * TopologyProjectionRegistryKey) -> TopologyProjectionRegistryResolution`.
     *
     * Ready establishes one detached symbol registry covering the exact content-identified
     * candidate generation. Rejected preserves [TopologyExtractionFailure] for unavailable PSI or
     * an inadmissible detached fact. Live Project, PSI, and K2 values remain inside this read
     * action; only detached symbols enter the registry cache.
     */
    private fun buildRegistry(
        project: Project,
        request: TopologyExtractionRequest,
        key: TopologyProjectionRegistryKey,
    ): TopologyProjectionRegistryResolution {
        val symbols = mutableListOf<TopologySymbol>()
        request.candidates.files.forEach { file ->
            val loaded = when (val lookup = load(project, file)) {
                is TopologyFileLoad.Loaded -> lookup.file
                TopologyFileLoad.Unavailable -> return TopologyProjectionRegistryResolution.Rejected(
                    TopologyExtractionFailure.FILE_UNAVAILABLE,
                )
            }
            loaded.declarations.forEach { declaration ->
                when (val projection = projectTopologySymbol(loaded.file, declaration)) {
                    is TopologySymbolProjection.Projected -> symbols += projection.symbol
                    TopologySymbolProjection.Unsupported -> Unit
                    TopologySymbolProjection.Rejected ->
                        return TopologyProjectionRegistryResolution.Rejected(
                            TopologyExtractionFailure.FACT_REJECTED,
                        )
                }
            }
        }
        return TopologyProjectionRegistryResolution.Ready(
            TopologyProjectionRegistry.from(key, symbols),
        )
    }

    /**
     * Proof transition: `(Project, TopologySourceFile) -> TopologyFileLoad`.
     *
     * Loaded establishes valid VFS and Kotlin PSI evidence for the exact admitted source file;
     * Unavailable is the closed expected failure. Raw VFS and PSI extraction stays in this K2
     * adapter.
     */
    private fun load(project: Project, file: TopologySourceFile): TopologyFileLoad {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolute.toString())
                          ?: return TopologyFileLoad.Unavailable
        if (!virtualFile.isValid || virtualFile.isDirectory) return TopologyFileLoad.Unavailable
        val psi = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                  ?: return TopologyFileLoad.Unavailable
        val declarations = PsiTreeUtil.collectElementsOfType(psi, KtNamedDeclaration::class.java)
            .filter(::isRepositoryDeclaration)
            .sortedBy { it.textRange.startOffset }
        return TopologyFileLoad.Loaded(LoadedTopologyFile(file, psi, declarations))
    }
}

private sealed interface TopologyFileLoad {
    data class Loaded(val file: LoadedTopologyFile) : TopologyFileLoad
    data object Unavailable : TopologyFileLoad
}

private sealed interface TopologyReferenceTarget {
    data class Found(
        val identity: CompilerSymbolIdentity,
    ) : TopologyReferenceTarget

    data object Unresolved : TopologyReferenceTarget
    data object Rejected : TopologyReferenceTarget
}

/**
 * Proof transition: `(KtReferenceExpression, TopologyProjectionRegistry) ->
 * TopologyReferenceTarget`.
 *
 * Found establishes one source-scoped refined compiler identity owned by the exact candidate
 * generation; unresolved and rejected targets stay closed. Raw reference resolution remains
 * inside the request-local K2 analysis session.
 */
private fun KtReferenceExpression.topologyTarget(
    registry: TopologyProjectionRegistry,
): TopologyReferenceTarget {
    for (native in references.filterIsInstance<KtReference>()) {
        when (val projection = analyze(native.element) {
            val symbol = native.resolveToSymbol()
                         ?: return@analyze TopologyK2IdentityProjection.Unsupported
            symbol.topologyIdentityProjection(registry)
        }) {
            is TopologyK2IdentityProjection.Projected ->
                return TopologyReferenceTarget.Found(projection.identity)
            TopologyK2IdentityProjection.Unsupported -> Unit
            TopologyK2IdentityProjection.Rejected -> return TopologyReferenceTarget.Rejected
        }
    }
    return TopologyReferenceTarget.Unresolved
}

private data class LoadedTopologyFile(
    val file: TopologySourceFile,
    val psi: KtFile,
    val declarations: List<KtNamedDeclaration>,
)

private sealed interface OwningTopologySymbol {
    data class Found(val symbol: TopologySymbol) : OwningTopologySymbol
    data object Unresolved : OwningTopologySymbol
}

/**
 * Proof transition: `(KtReferenceExpression, declaration symbols) -> OwningTopologySymbol`.
 *
 * Found preserves the nearest enclosing admitted declaration symbol; Unresolved is the closed
 * absence state. Raw PSI parent traversal stays within this file extraction boundary.
 */
private fun KtReferenceExpression.owningSymbol(
    symbols: Map<KtNamedDeclaration, TopologySymbol>,
): OwningTopologySymbol {
    val symbol = generateSequence(parent) { it.parent }
        .filterIsInstance<KtNamedDeclaration>()
        .mapNotNull(symbols::get)
        .firstOrNull()
    return if (symbol == null) OwningTopologySymbol.Unresolved
    else OwningTopologySymbol.Found(symbol)
}

private fun KtReferenceExpression.edgeKind(): TopologyEdgeKind {
    val call = PsiTreeUtil.getParentOfType(this, KtCallElement::class.java, false)
    if (call?.calleeExpression?.textRange?.contains(textRange) == true) return TopologyEdgeKind.CALL
    if (PsiTreeUtil.getParentOfType(this, KtSuperTypeListEntry::class.java, false) != null) {
        return TopologyEdgeKind.INHERITANCE
    }
    if (PsiTreeUtil.getParentOfType(this, KtTypeReference::class.java, false) != null) {
        return TopologyEdgeKind.TYPE_USE
    }
    return TopologyEdgeKind.REFERENCE
}

/**
 * Proof transition: `KtReferenceExpression -> TopologyReferenceOccurrence`.
 *
 * Admitted establishes a non-empty direct or enclosing super-type call source range. Rejected
 * closes synthetic references with no physical source anchor. Raw PSI remains inside the
 * request-local IntelliJ extraction boundary.
 */
private fun KtReferenceExpression.topologyOccurrence(): TopologyReferenceOccurrence {
    val enclosing = PsiTreeUtil.getParentOfType(
        this,
        KtSuperTypeCallEntry::class.java,
        false,
    )
    val enclosingRange = if (enclosing == null) {
        EnclosingSuperTypeCallRange.Unavailable
    } else {
        EnclosingSuperTypeCallRange.Observed(enclosing.textRange)
    }
    return TopologyReferenceOccurrence.refine(textRange, enclosingRange)
}

private fun failed(failure: TopologyExtractionFailure): TopologyFileExtraction.Failed =
    TopologyFileExtraction.Failed(failure)
