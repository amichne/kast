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
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTypeReference
import java.nio.file.Path

/** Public native K2 boundary for one exact topology candidate. */
class IntellijTopologyFileExtractor {
    /**
     * Proof transition: `(Project, PublishedWorkspace, TopologyExtractionRequest) ->
     * TopologyFileExtraction`.
     *
     * Complete output establishes detached compiler symbols and repository-internal edges for the
     * exact admitted file. All candidate PSI is loaded only to resolve cross-file targets; only the
     * requested file receives terminal output. [TopologyExtractionFailure] is the closed expected
     * failure. Cancellation propagates and live Project, VFS, PSI, and K2 values never leave the
     * read action.
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
        val loaded = request.candidates.files.map { file ->
            when (val lookup = load(project, file)) {
                is TopologyFileLoad.Loaded -> lookup.file
                TopologyFileLoad.Unavailable ->
                    return failed(TopologyExtractionFailure.FILE_UNAVAILABLE)
            }
        }
        val projected = loaded.flatMap { source ->
            source.declarations.mapNotNull { declaration ->
                when (val symbol = projectTopologySymbol(source.file, declaration)) {
                    is TopologySymbolProjection.Projected -> declaration to symbol.symbol
                    TopologySymbolProjection.Unsupported -> null
                    TopologySymbolProjection.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
            }
        }
        val symbolByDeclaration = projected.toMap()
        val symbolByIdentity = projected.map { it.second }
            .associateBy { it.evidence.compilerIdentity }
        val requested = loaded.singleOrNull { it.file == request.file }
                        ?: return failed(TopologyExtractionFailure.FILE_UNAVAILABLE)
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
                val targetIdentity = when (val resolved = reference.topologyTarget()) {
                    is TopologyReferenceTarget.Found -> resolved.identity
                    TopologyReferenceTarget.Unresolved -> return@forEach
                    TopologyReferenceTarget.Rejected ->
                        return failed(TopologyExtractionFailure.FACT_REJECTED)
                }
                val target = symbolByIdentity[targetIdentity] ?: return@forEach
                val kind = reference.edgeKind()
                val edge = TopologyEdge.fromBoundary(
                    kind,
                    source,
                    target,
                    reference.textRange.startOffset,
                    reference.textRange.endOffset,
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
                declaration.directOverrideTopologyIdentities()
            ) {
                is TopologyOverrideProjection.Projected -> projectedOverrides.identities
                TopologyOverrideProjection.Rejected ->
                    return failed(TopologyExtractionFailure.FACT_REJECTED)
            }
            overrides.forEach { targetIdentity ->
                val target = symbolByIdentity[targetIdentity] ?: return@forEach
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
 * Proof transition: `KtReferenceExpression -> TopologyReferenceTarget`.
 *
 * Found establishes one refined detached compiler identity; unresolved and rejected targets stay
 * closed. Raw reference resolution remains inside the request-local K2 analysis session.
 */
private fun KtReferenceExpression.topologyTarget(): TopologyReferenceTarget {
    for (native in references.filterIsInstance<KtReference>()) {
        when (val projection = analyze(native.element) {
            val symbol = native.resolveToSymbol()
                         ?: return@analyze TopologyK2IdentityProjection.Unsupported
            symbol.topologyIdentityProjection()
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

private fun failed(failure: TopologyExtractionFailure): TopologyFileExtraction.Failed =
    TopologyFileExtraction.Failed(failure)
