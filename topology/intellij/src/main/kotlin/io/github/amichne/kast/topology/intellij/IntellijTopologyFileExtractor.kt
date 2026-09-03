package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
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
class IntellijTopologyFileExtractor internal constructor(
    private val mismatchRetrier: TopologyVfsMismatchRetrier,
) {
    constructor() : this(
        TopologyVfsMismatchRetrier(InstalledTopologySourceRootVfsSynchronizer),
    )

    private val registries = TopologyProjectionRegistryCache()
    private val readEpochs = TopologyReadEpochCache()

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
    ): TopologyFileExtraction = try {
        mismatchRetrier.extract(current, request) {
            extractOnce(project, current, request)
        }
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        failed(request.file, TopologyExtractionFailure.COMPILER_UNAVAILABLE)
    } catch (_: LinkageError) {
        failed(request.file, TopologyExtractionFailure.COMPILER_UNAVAILABLE)
    }

    /** One short read-action attempt; a retry always enters a fresh read action. */
    private suspend fun extractOnce(
        project: Project,
        current: PublishedWorkspace,
        request: TopologyExtractionRequest,
    ): TopologyFileExtraction {
        if (
            project.isDisposed || current.readLease != request.candidates.workspace.lease ||
            current.sourceState != request.candidates.workspace.sourceState
        ) {
            return failed(request.file, TopologyExtractionFailure.PROJECT_UNAVAILABLE)
        }
        return readAction {
            if (DumbService.isDumb(project)) {
                return@readAction failed(
                    request.file,
                    TopologyExtractionFailure.COMPILER_UNAVAILABLE,
                )
            }
            extractInReadAction(project, request)
        }
    }

    private fun extractInReadAction(
        project: Project,
        request: TopologyExtractionRequest,
    ): TopologyFileExtraction {
        val registryKey = TopologyProjectionRegistryKey.from(request.candidates)
        return readEpochs.resolve(registryKey, request.file) {
            extractUncached(project, request, registryKey)
        }
    }

    private fun extractUncached(
        project: Project,
        request: TopologyExtractionRequest,
        registryKey: TopologyProjectionRegistryKey,
    ): TopologyFileExtraction {
        val registry = when (
            val resolution = registries.resolve(registryKey) {
                buildRegistry(project, request, registryKey)
            }
        ) {
            is TopologyProjectionRegistryResolution.Ready -> resolution.registry
            is TopologyProjectionRegistryResolution.Rejected -> return failed(
                resolution.file,
                resolution.failure,
            )
        }
        val requested = when (val lookup = load(project, request.file)) {
            is TopologyFileLoad.Loaded -> lookup.file
            TopologyFileLoad.Unavailable ->
                return failed(request.file, TopologyExtractionFailure.FILE_UNAVAILABLE)
            TopologyFileLoad.DocumentDirty ->
                return failed(request.file, TopologyExtractionFailure.DOCUMENT_DIRTY)
            TopologyFileLoad.PsiDocumentUncommitted ->
                return failed(
                    request.file,
                    TopologyExtractionFailure.PSI_DOCUMENT_UNCOMMITTED,
                )
            TopologyFileLoad.VfsContentMismatch ->
                return failed(request.file, TopologyExtractionFailure.VFS_CONTENT_MISMATCH)
            TopologyFileLoad.NotKotlinPsi ->
                return failed(request.file, TopologyExtractionFailure.NOT_KOTLIN_PSI)
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
                        return failed(
                            request.file,
                            TopologyExtractionFailure.DECLARATION_EVIDENCE_REJECTED,
                        )
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
                val target = when (val resolved = reference.topologyTarget(registry)) {
                    is TopologyReferenceTarget.Found -> resolved.symbol
                    TopologyReferenceTarget.Unresolved -> return@forEach
                    TopologyReferenceTarget.CompilerIdentityMismatch ->
                        return failed(
                            request.file,
                            TopologyExtractionFailure.COMPILER_IDENTITY_MISMATCH,
                        )
                    TopologyReferenceTarget.Rejected ->
                        return failed(
                            request.file,
                            TopologyExtractionFailure.REFERENCE_TARGET_REJECTED,
                        )
                }
                val kind = reference.edgeKind()
                val occurrence = when (val refined = reference.topologyOccurrence()) {
                    is TopologyReferenceOccurrence.Admitted -> refined.range
                    TopologyReferenceOccurrence.Rejected ->
                        return failed(request.file, TopologyExtractionFailure.OCCURRENCE_REJECTED)
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
                        return failed(request.file, TopologyExtractionFailure.EDGE_REJECTED)
                }
            }
        requested.declarations.forEach { declaration ->
            val source = symbolByDeclaration[declaration] ?: return@forEach
            val overrides = when (val projectedOverrides =
                declaration.directOverrideTopologyIdentities(registry)
            ) {
                is TopologyOverrideProjection.Projected -> projectedOverrides.symbols
                TopologyOverrideProjection.CompilerIdentityMismatch ->
                    return failed(
                        request.file,
                        TopologyExtractionFailure.COMPILER_IDENTITY_MISMATCH,
                    )
                TopologyOverrideProjection.Rejected ->
                    return failed(request.file, TopologyExtractionFailure.OVERRIDE_REJECTED)
            }
            overrides.forEach { target ->
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
                        return failed(request.file, TopologyExtractionFailure.EDGE_REJECTED)
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
            is Refinement.Rejected -> failed(
                request.file,
                TopologyExtractionFailure.FILE_ADMISSION_REJECTED,
            )
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
                    file,
                    TopologyExtractionFailure.FILE_UNAVAILABLE,
                )
                TopologyFileLoad.DocumentDirty ->
                    return TopologyProjectionRegistryResolution.Rejected(
                        file,
                        TopologyExtractionFailure.DOCUMENT_DIRTY,
                    )
                TopologyFileLoad.PsiDocumentUncommitted ->
                    return TopologyProjectionRegistryResolution.Rejected(
                        file,
                        TopologyExtractionFailure.PSI_DOCUMENT_UNCOMMITTED,
                    )
                TopologyFileLoad.VfsContentMismatch ->
                    return TopologyProjectionRegistryResolution.Rejected(
                        file,
                        TopologyExtractionFailure.VFS_CONTENT_MISMATCH,
                    )
                TopologyFileLoad.NotKotlinPsi ->
                    return TopologyProjectionRegistryResolution.Rejected(
                        file,
                        TopologyExtractionFailure.NOT_KOTLIN_PSI,
                    )
            }
            loaded.declarations.forEach { declaration ->
                when (val projection = projectTopologySymbol(loaded.file, declaration)) {
                    is TopologySymbolProjection.Projected -> symbols += projection.symbol
                    TopologySymbolProjection.Unsupported -> Unit
                    TopologySymbolProjection.Rejected ->
                        return TopologyProjectionRegistryResolution.Rejected(
                            file,
                            TopologyExtractionFailure.DECLARATION_EVIDENCE_REJECTED,
                        )
                }
            }
        }
        return when (val registry = TopologyProjectionRegistry.from(key, symbols)) {
            is Refinement.Refined -> TopologyProjectionRegistryResolution.Ready(registry.value)
            is Refinement.Rejected -> TopologyProjectionRegistryResolution.Rejected(
                request.file,
                TopologyExtractionFailure.PROJECTION_REGISTRY_REJECTED,
            )
        }
    }

    /**
     * Proof transition: `(Project, TopologySourceFile) -> TopologyFileLoad`.
     *
     * Loaded establishes valid VFS and Kotlin PSI evidence whose live bytes retain the exact
     * admitted source-content identity. Unavailable, dirty document, uncommitted PSI document,
     * VFS mismatch, and non-Kotlin PSI are distinct closed failures. Raw VFS bytes, documents,
     * and PSI extraction stay in this K2 adapter.
     */
    private fun load(project: Project, file: TopologySourceFile): TopologyFileLoad {
        val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolute.toString())
                          ?: return TopologyFileLoad.Unavailable
        if (!virtualFile.isValid || virtualFile.isDirectory) return TopologyFileLoad.Unavailable
        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
        if (document != null) {
            when (TopologyDocumentReadiness.observe(
                FileDocumentManager.getInstance().isFileModified(virtualFile),
                PsiDocumentManager.getInstance(project).isCommitted(document),
            )) {
                TopologyDocumentReadiness.READY -> Unit
                TopologyDocumentReadiness.DOCUMENT_DIRTY ->
                    return TopologyFileLoad.DocumentDirty
                TopologyDocumentReadiness.PSI_DOCUMENT_UNCOMMITTED ->
                    return TopologyFileLoad.PsiDocumentUncommitted
            }
        }
        val content = try {
            virtualFile.contentsToByteArray(false)
        } catch (_: java.io.IOException) {
            return TopologyFileLoad.Unavailable
        }
        val identified = when (val validation = LiveTopologySourceContent.validate(file, content)) {
            is Refinement.Refined -> validation.value
            is Refinement.Rejected -> return TopologyFileLoad.VfsContentMismatch
        }
        val psi = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                  ?: return TopologyFileLoad.NotKotlinPsi
        val declarations = PsiTreeUtil.collectElementsOfType(psi, KtNamedDeclaration::class.java)
            .filter(::isRepositoryDeclaration)
            .sortedBy { it.textRange.startOffset }
        return TopologyFileLoad.Loaded(LoadedTopologyFile(identified, psi, declarations))
    }
}

private sealed interface TopologyFileLoad {
    data class Loaded(val file: LoadedTopologyFile) : TopologyFileLoad
    data object Unavailable : TopologyFileLoad
    data object DocumentDirty : TopologyFileLoad
    data object PsiDocumentUncommitted : TopologyFileLoad
    data object VfsContentMismatch : TopologyFileLoad
    data object NotKotlinPsi : TopologyFileLoad
}

private sealed interface TopologyReferenceTarget {
    data class Found(
        val symbol: TopologySymbol,
    ) : TopologyReferenceTarget

    data object Unresolved : TopologyReferenceTarget
    data object CompilerIdentityMismatch : TopologyReferenceTarget
    data object Rejected : TopologyReferenceTarget
}

/**
 * Proof transition: `(KtReferenceExpression, TopologyProjectionRegistry) ->
 * TopologyReferenceTarget`.
 *
 * Found establishes one exact location-bearing topology symbol owned by the exact candidate
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
                return TopologyReferenceTarget.Found(projection.symbol)
            TopologyK2IdentityProjection.Unsupported -> Unit
            TopologyK2IdentityProjection.CompilerIdentityMismatch ->
                return TopologyReferenceTarget.CompilerIdentityMismatch
            TopologyK2IdentityProjection.Rejected -> return TopologyReferenceTarget.Rejected
        }
    }
    return TopologyReferenceTarget.Unresolved
}

private data class LoadedTopologyFile(
    val content: LiveTopologySourceContent,
    val psi: KtFile,
    val declarations: List<KtNamedDeclaration>,
) {
    val file: TopologySourceFile = content.file
}

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

private fun failed(
    file: TopologySourceFile,
    failure: TopologyExtractionFailure,
): TopologyFileExtraction.Failed = TopologyFileExtraction.Failed(file, failure)
