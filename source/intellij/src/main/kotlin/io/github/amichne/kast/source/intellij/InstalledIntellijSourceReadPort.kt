@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.source.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EnclosingRegionKind
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.source.contract.RevalidatedSourceSelector
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.RevalidatedSymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalSemanticProjectRoot
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameter

private const val MAX_NATIVE_SOURCE_ENTITY_WORK = 10_000

/** Installed source-read port that locates one already-open exact-root IntelliJ project. */
class InstalledIntellijSourceReadPort private constructor(
    private val delegate: SourceReadPort,
) : SourceReadPort by delegate {
    companion object {
        fun create(root: CanonicalSemanticProjectRoot): InstalledIntellijSourceReadPort =
            InstalledIntellijSourceReadPort(
                IntellijSourceReadPort(
                    IntellijSourceRegionAccess { context, request, cursor ->
                        val project = exactProject(root)
                            ?: return@IntellijSourceRegionAccess regionRejected(
                                IntellijSourceReadRejection.SOURCE_UNAVAILABLE,
                            )
                        LiveIntellijSourceRegionAccess(project).select(context, request, cursor)
                    },
                ),
            )
    }
}

private class LiveIntellijSourceRegionAccess(
    private val project: Project,
) : IntellijSourceRegionAccess {
    override suspend fun select(
        context: SourceReadContext,
        request: SourceReadRequest,
        cursor: IntellijSourceEntityCursor,
    ): IntellijSourceRegionAccessResult {
        if (project.isDisposed) return regionRejected(IntellijSourceReadRejection.SOURCE_UNAVAILABLE)
        if (DumbService.isDumb(project)) {
            return regionRejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        }
        return try {
            readAction { selectInReadAction(context, request, cursor) }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            regionRejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        } catch (_: RuntimeException) {
            regionRejected(IntellijSourceReadRejection.PROVIDER_FAILURE)
        }
    }

    private fun selectInReadAction(
        context: SourceReadContext,
        request: SourceReadRequest,
        cursor: IntellijSourceEntityCursor,
    ): IntellijSourceRegionAccessResult {
        val fileIdentity = when (val anchor = request.anchor) {
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Symbol ->
                anchor.selector.file as? SymbolDiscoveryFileIdentity.Workspace
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Source ->
                anchor.selector.snapshot.file
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Candidate ->
                anchor.selector.workspaceFile()
        } ?: return regionRejected(IntellijSourceReadRejection.OUTSIDE_SOURCE_SCOPE)
        val document = when (val admission = liveDocument(context, fileIdentity)) {
            is LiveSourceDocumentAdmission.Admitted -> admission.document
            is LiveSourceDocumentAdmission.Rejected -> return regionRejected(admission.reason)
        }
        val anchor = when (val requested = request.anchor) {
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Symbol ->
                admitSymbolAnchor(document, requested.selector)
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Source ->
                admitSourceAnchor(document, requested.selector)
            is io.github.amichne.kast.source.contract.SourceReadAnchor.Candidate ->
                admitCandidateAnchor(document, requested.selector)
        }
        val admittedAnchor = when (anchor) {
            is LiveSourceAnchorResult.Admitted -> anchor.anchor
            is LiveSourceAnchorResult.Rejected -> return regionRejected(anchor.reason)
        }
        val selected = deriveRegion(admittedAnchor, request.region)
            ?: return regionRejected(IntellijSourceReadRejection.REGION_ABSENT)
        val selectedRange = document.snapshot.sourceRange(selected.range)
            ?: return regionRejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        val regionSelector = issueRegionSelector(
            admittedAnchor.selector,
            selectedRange,
            selected.kind,
        ) ?: return regionRejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        val entityPage = when (
            val projected = projectEntities(
                document,
                selected,
                regionSelector,
                request,
                cursor,
            )
        ) {
            is NativeSourceEntityProjection.Projected -> projected.page
            is NativeSourceEntityProjection.Rejected -> return regionRejected(projected.reason)
        }
        return when (
            val capture = IntellijSelectedSourceCapture.create(
                document.snapshot,
                admittedAnchor.selector,
                regionSelector,
                document.text,
                entityPage,
            )
        ) {
            is Refinement.Refined -> IntellijSourceRegionAccessResult.Selected(capture.value)
            is Refinement.Rejected -> regionRejected(capture.failure)
        }
    }

    private fun liveDocument(
        context: SourceReadContext,
        fileIdentity: SymbolDiscoveryFileIdentity.Workspace,
    ): LiveSourceDocumentAdmission {
        val file = VirtualFileManager.getInstance().findFileByNioPath(Path.of(fileIdentity.path.value))
            ?.takeIf { it.isValid }
            ?: return LiveSourceDocumentAdmission.Rejected(
                IntellijSourceReadRejection.SOURCE_UNAVAILABLE,
            )
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)
            ?: return LiveSourceDocumentAdmission.Rejected(
                IntellijSourceReadRejection.SOURCE_UNAVAILABLE,
            )
        if (documents.isFileModified(file)) {
            return LiveSourceDocumentAdmission.Rejected(IntellijSourceReadRejection.DOCUMENT_DIRTY)
        }
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
            return LiveSourceDocumentAdmission.Rejected(
                IntellijSourceReadRejection.PSI_DOCUMENT_UNCOMMITTED,
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile
            ?: return LiveSourceDocumentAdmission.Rejected(
                IntellijSourceReadRejection.SOURCE_UNAVAILABLE,
            )
        val text = document.charsSequence.toString()
        val length = when (val parsed = Utf16CodeUnitCount.parse(text.length)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return LiveSourceDocumentAdmission.Rejected(
                IntellijSourceReadRejection.CONTRACT_VIOLATION,
            )
        }
        return LiveSourceDocumentAdmission.Admitted(
            LiveSourceDocument(
                SourceSnapshot.create(
                    context.lease,
                    context.sourceState,
                    fileIdentity,
                    SourceTextIdentity.fromNormalizedCommittedText(text),
                    length,
                ),
                text,
                psiFile,
            ),
        )
    }

    private fun admitCandidateAnchor(
        document: LiveSourceDocument,
        selector: CandidateSelector,
    ): LiveSourceAnchorResult {
        if (selector.lease != document.snapshot.lease) {
            return LiveSourceAnchorResult.Rejected(IntellijSourceReadRejection.STALE_GENERATION)
        }
        return when (selector) {
            is CandidateSelector.File -> {
                val range = document.snapshot.sourceRange(0, document.text.length)
                    ?: return LiveSourceAnchorResult.Rejected(
                        IntellijSourceReadRejection.CONTRACT_VIOLATION,
                    )
                LiveSourceAnchorResult.Admitted(
                    LiveSourceAnchor(
                        SourceSelector.issueRoot(range, SourceRegionKind.FILE),
                        document.psiFile,
                    ),
                )
            }
            is CandidateSelector.Range -> {
                val range = document.snapshot.sourceRange(
                    selector.startInclusive.value,
                    selector.endExclusive.value,
                ) ?: return LiveSourceAnchorResult.Rejected(
                    IntellijSourceReadRejection.ANCHOR_NOT_FOUND,
                )
                val element = if (selector.startInclusive.value < document.text.length) {
                    document.psiFile.findElementAt(selector.startInclusive.value)
                } else {
                    document.psiFile
                } ?: return LiveSourceAnchorResult.Rejected(
                    IntellijSourceReadRejection.ANCHOR_NOT_FOUND,
                )
                LiveSourceAnchorResult.Admitted(
                    LiveSourceAnchor(
                        SourceSelector.issueRoot(range, SourceRegionKind.ANCHOR),
                        element,
                    ),
                )
            }
            is CandidateSelector.Declaration -> {
                val declaration = when (
                    val lookup = exactCandidateDeclaration(document.psiFile, selector)
                ) {
                    is CandidateDeclarationLookup.Found -> lookup.declaration
                    CandidateDeclarationLookup.Missing ->
                        return LiveSourceAnchorResult.Rejected(
                            IntellijSourceReadRejection.ANCHOR_NOT_FOUND,
                        )
                    CandidateDeclarationLookup.Ambiguous ->
                        return LiveSourceAnchorResult.Rejected(
                            IntellijSourceReadRejection.AMBIGUOUS_ANCHOR,
                        )
                }
                val range = document.snapshot.sourceRange(declaration.textRange)
                    ?: return LiveSourceAnchorResult.Rejected(
                        IntellijSourceReadRejection.CONTRACT_VIOLATION,
                    )
                LiveSourceAnchorResult.Admitted(
                    LiveSourceAnchor(
                        SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION),
                        declaration,
                    ),
                )
            }
        }
    }

    private fun admitSymbolAnchor(
        document: LiveSourceDocument,
        selector: SymbolSelector,
    ): LiveSourceAnchorResult {
        if (selector.lease != document.snapshot.lease) {
            return LiveSourceAnchorResult.Rejected(IntellijSourceReadRejection.STALE_GENERATION)
        }
        val declaration = exactDeclaration(document.psiFile, selector)
            ?: return LiveSourceAnchorResult.Rejected(IntellijSourceReadRejection.ANCHOR_NOT_FOUND)
        val evidence = declaration.compilerEvidence(selector)
            ?: return LiveSourceAnchorResult.Rejected(
                IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE,
            )
        when (RevalidatedSymbolSelector.validate(selector, evidence)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return LiveSourceAnchorResult.Rejected(
                IntellijSourceReadRejection.DECLARATION_MOVED_OR_CHANGED,
            )
        }
        val range = document.snapshot.sourceRange(declaration.textRange)
            ?: return LiveSourceAnchorResult.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        return LiveSourceAnchorResult.Admitted(
            LiveSourceAnchor(
                SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION),
                declaration,
            ),
        )
    }

    private fun admitSourceAnchor(
        document: LiveSourceDocument,
        selector: SourceSelector,
    ): LiveSourceAnchorResult {
        when (RevalidatedSourceSelector.validate(selector, document.snapshot)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return LiveSourceAnchorResult.Rejected(
                IntellijSourceReadRejection.DECLARATION_MOVED_OR_CHANGED,
            )
        }
        val element = if (selector.range.startInclusive.value < document.text.length) {
            document.psiFile.findElementAt(selector.range.startInclusive.value)
        } else {
            document.psiFile
        }
        val regionSelector = when (selector) {
            is SourceSelector.RootRegion,
            is SourceSelector.NestedRegion,
                -> selector
            is SourceSelector.Entity -> SourceSelector.issueRoot(
                selector.range,
                SourceRegionKind.ANCHOR,
            )
        }
        return LiveSourceAnchorResult.Admitted(LiveSourceAnchor(regionSelector, element))
    }
}

private data class LiveSourceDocument(
    val snapshot: SourceSnapshot,
    val text: String,
    val psiFile: KtFile,
)

private sealed interface LiveSourceDocumentAdmission {
    data class Admitted(val document: LiveSourceDocument) : LiveSourceDocumentAdmission
    data class Rejected(val reason: IntellijSourceReadRejection) : LiveSourceDocumentAdmission
}

private data class LiveSourceAnchor(
    val selector: SourceSelector,
    val element: PsiElement?,
)

private sealed interface LiveSourceAnchorResult {
    data class Admitted(val anchor: LiveSourceAnchor) : LiveSourceAnchorResult
    data class Rejected(val reason: IntellijSourceReadRejection) : LiveSourceAnchorResult
}

private data class NativeSourceRegion(
    val range: TextRange,
    val kind: SourceRegionKind,
    val element: PsiElement,
)

private fun deriveRegion(
    anchor: LiveSourceAnchor,
    selection: RegionSelection,
): NativeSourceRegion? = when (selection) {
    RegionSelection.Anchor -> {
        val range = TextRange(
            anchor.selector.range.startInclusive.value,
            anchor.selector.range.endExclusive.value,
        )
        anchor.element?.let { element ->
            NativeSourceRegion(
                range,
                anchor.selector.nativeRegionKind(),
                element.exactRegionElement(range),
            )
        }
    }
    RegionSelection.File -> anchor.element?.containingFile?.let { file ->
        NativeSourceRegion(
            TextRange(0, anchor.selector.snapshot.length.value),
            SourceRegionKind.FILE,
            file,
        )
    }
    is RegionSelection.Body -> when (selection.kind) {
        BodyKind.CALLABLE -> anchor.element.nearestCallable()?.bodyExpression
            ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.CALLABLE_BODY, it) }
        BodyKind.CLASS -> anchor.element.nearestClass()?.body
            ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.CLASS_BODY, it) }
    }
    is RegionSelection.Enclosing -> when (selection.kind) {
        EnclosingRegionKind.DECLARATION -> anchor.element.strictAncestors(anchor.selector)
            .filterIsInstance<KtDeclaration>()
            .firstOrNull()
            ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.DECLARATION, it) }
        EnclosingRegionKind.CALLABLE_BODY -> anchor.element.strictAncestors(anchor.selector)
            .filterIsInstance<KtExpression>()
            .firstOrNull { expression ->
                (expression.parent as? KtDeclarationWithBody)?.bodyExpression === expression
            }
            ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.CALLABLE_BODY, it) }
        EnclosingRegionKind.CLASS_BODY -> anchor.element.strictAncestors(anchor.selector)
            .filterIsInstance<KtClassBody>()
            .firstOrNull()
            ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.CLASS_BODY, it) }
    }
}

private fun PsiElement.exactRegionElement(range: TextRange): PsiElement =
    generateSequence(this) { it.parent }
        .firstOrNull { element -> element.textRange == range }
        ?: this

private fun PsiElement?.nearestCallable(): KtDeclarationWithBody? =
    generateSequence(this) { it.parent }.filterIsInstance<KtDeclarationWithBody>().firstOrNull()

private fun PsiElement?.nearestClass(): KtClassOrObject? =
    generateSequence(this) { it.parent }.filterIsInstance<KtClassOrObject>().firstOrNull()

private fun PsiElement?.strictAncestors(selector: SourceSelector): Sequence<PsiElement> =
    generateSequence(this) { it.parent }.filter { element ->
        element.textRange.startOffset < selector.range.startInclusive.value ||
            element.textRange.endOffset > selector.range.endExclusive.value
    }

private fun SourceSelector.nativeRegionKind(): SourceRegionKind = when (this) {
    is SourceSelector.RootRegion -> kind
    is SourceSelector.NestedRegion -> kind
    is SourceSelector.Entity -> SourceRegionKind.ANCHOR
}

private fun SourceSnapshot.sourceRange(textRange: TextRange): SourceRange? =
    sourceRange(textRange.startOffset, textRange.endOffset)

private fun SourceSnapshot.sourceRange(start: Int, end: Int): SourceRange? {
    val startOffset = when (val parsed = Utf16CodeUnitOffset.parse(start)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val endOffset = when (val parsed = Utf16CodeUnitOffset.parse(end)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    return when (val admitted = SourceRange.create(this, startOffset, endOffset)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> null
    }
}

private fun issueRegionSelector(
    anchor: SourceSelector,
    range: SourceRange,
    kind: SourceRegionKind,
): SourceSelector? {
    val sameRange = range.startInclusive == anchor.range.startInclusive &&
        range.endExclusive == anchor.range.endExclusive
    if (sameRange && anchor.nativeRegionKind() == kind && anchor !is SourceSelector.Entity) {
        return anchor
    }
    val insideAnchor = range.startInclusive >= anchor.range.startInclusive &&
        range.endExclusive <= anchor.range.endExclusive
    return if (insideAnchor) {
        when (val issued = SourceSelector.issueNested(anchor, range, kind)) {
            is Refinement.Refined -> issued.value
            is Refinement.Rejected -> null
        }
    } else {
        SourceSelector.issueRoot(range, kind)
    }
}

private sealed interface NativeSourceEntityProjection {
    data class Projected(val page: IntellijSourceEntityPage) : NativeSourceEntityProjection
    data class Rejected(val reason: IntellijSourceReadRejection) : NativeSourceEntityProjection
}

private sealed interface NativeSourceEntityEnumeration {
    val entities: List<SourceEntity>

    data class Complete(
        override val entities: List<SourceEntity>,
    ) : NativeSourceEntityEnumeration

    data class Qualified(
        override val entities: List<SourceEntity>,
        val limitation: SourceReadLimitation,
    ) : NativeSourceEntityEnumeration

    data class Rejected(
        val reason: IntellijSourceReadRejection,
    ) : NativeSourceEntityEnumeration {
        override val entities: List<SourceEntity> = emptyList()
    }
}

private enum class NativeVisibilityTarget {
    DECLARATION,
    PRIMARY_CONSTRUCTOR_PROPERTY,
}

private data class NativeStructuralParent(
    val selector: SourceSelector,
    val depth: Int,
)

private fun projectEntities(
    document: LiveSourceDocument,
    region: NativeSourceRegion,
    regionSelector: SourceSelector,
    request: SourceReadRequest,
    cursor: IntellijSourceEntityCursor,
): NativeSourceEntityProjection {
    if (request.entities == EntitySelection.None) {
        return NativeSourceEntityProjection.Projected(IntellijSourceEntityPage.empty())
    }
    val matching = request.entities as EntitySelection.Matching
    val includeDeclarations = matching.filters.any { it is EntityFilter.Declarations }
    val includeParameters = EntityFilter.Parameters in matching.filters
    val includeCalls = EntityFilter.Calls in matching.filters
    val includeReferences = EntityFilter.References in matching.filters
    val requiresK2 = includeDeclarations || includeCalls || includeReferences
    val enumeration = if (requiresK2) {
        analyze(document.psiFile) {
            NativeSourceEntityEnumerator(
                document,
                region,
                regionSelector,
                includeDeclarations,
                includeParameters,
                includeCalls,
                includeReferences,
                visibility = { declaration, target ->
                    val visibility = when (target) {
                        NativeVisibilityTarget.DECLARATION ->
                            declaration.symbol.visibility
                        NativeVisibilityTarget.PRIMARY_CONSTRUCTOR_PROPERTY ->
                            ((declaration as? KtParameter)?.symbol as? KaValueParameterSymbol)
                                ?.generatedPrimaryConstructorProperty
                                ?.visibility
                    }
                    visibility?.sourceVisibility()
                },
                target = { reference ->
                    val targets = reference.mainReference.resolveToSymbols().toList()
                    when (targets.size) {
                        0 -> SourceEntityTarget.Unresolved(
                            CompilerUnresolvedReason.NAME_NOT_FOUND,
                        )
                        1 -> targets.single().sourceEntityTarget(document)
                        else -> SourceEntityTarget.Unresolved(
                            CompilerUnresolvedReason.AMBIGUOUS,
                        )
                    }
                },
            ).enumerate()
        }
    } else {
        NativeSourceEntityEnumerator(
            document,
            region,
            regionSelector,
            includeDeclarations,
            includeParameters,
            includeCalls,
            includeReferences,
            { _, _ -> null },
            { SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET) },
        ).enumerate()
    }
    return when (enumeration) {
        is NativeSourceEntityEnumeration.Rejected ->
            NativeSourceEntityProjection.Rejected(enumeration.reason)
        is NativeSourceEntityEnumeration.Complete -> NativeSourceEntityProjection.Projected(
            IntellijSourceEntityPage.select(
                enumeration.entities.asSequence(),
                request.entities,
                cursor,
                request.entityLimit,
            ),
        )
        is NativeSourceEntityEnumeration.Qualified -> NativeSourceEntityProjection.Projected(
            IntellijSourceEntityPage.select(
                enumeration.entities.asSequence(),
                request.entities,
                cursor,
                request.entityLimit,
            ).withLimitation(enumeration.limitation),
        )
    }
}

private class NativeSourceEntityEnumerator(
    private val document: LiveSourceDocument,
    private val region: NativeSourceRegion,
    private val regionSelector: SourceSelector,
    private val includeDeclarations: Boolean,
    private val includeParameters: Boolean,
    private val includeCalls: Boolean,
    private val includeReferences: Boolean,
    private val visibility: (KtNamedDeclaration, NativeVisibilityTarget) -> DeclarationVisibility?,
    private val target: (KtNameReferenceExpression) -> SourceEntityTarget,
) {
    private val entities = ArrayList<SourceEntity>()
    private var examined = 0
    private var limitation: SourceReadLimitation? = null
    private var rejection: IntellijSourceReadRejection? = null

    fun enumerate(): NativeSourceEntityEnumeration {
        val rootParent = NativeStructuralParent(regionSelector, 0)
        val classParent = (region.element as? KtClassOrObject)?.let { rootParent }
        visitChildren(region.element, rootParent, classParent)
        val rejected = rejection
        if (rejected != null) return NativeSourceEntityEnumeration.Rejected(rejected)
        val limited = limitation
        return if (limited == null) {
            NativeSourceEntityEnumeration.Complete(entities.toList())
        } else {
            NativeSourceEntityEnumeration.Qualified(entities.toList(), limited)
        }
    }

    private fun visitChildren(
        container: PsiElement,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        for (child in container.children) {
            if (stopped()) return
            visit(child, parent, classPropertyParent)
        }
    }

    private fun visit(
        element: PsiElement,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        if (!examine()) return
        if (!element.textRange.intersects(region.range)) return
        if (!region.range.contains(element.textRange)) {
            visitChildren(element, parent, classPropertyParent)
            return
        }
        when {
            element is KtCallExpression -> {
                visitCall(element, parent, classPropertyParent)
            }
            element is KtNameReferenceExpression -> {
                visitReference(element, parent)
                if (!stopped()) visitChildren(element, parent, classPropertyParent)
            }
            element is KtLambdaExpression -> {
                visitAnonymousCallable(element.bodyExpression, parent, classPropertyParent)
            }
            element is KtNamedFunction && element.name == null -> {
                visitAnonymousCallable(element.bodyExpression, parent, classPropertyParent)
            }
            element is KtParameter -> {
                visitParameter(element, parent, classPropertyParent)
                if (!stopped()) visitChildren(element, parent, classPropertyParent)
            }
            element is KtTypeParameter -> Unit
            element is KtDestructuringDeclaration -> {
                if (includeDeclarations) {
                    limitation = SourceReadLimitation.UNSUPPORTED_ENTITY
                } else {
                    visitChildren(element, parent, classPropertyParent)
                }
            }
            element is KtNamedDeclaration -> {
                val kind = element.sourceDeclarationKind()
                if (kind == null) {
                    visitChildren(element, parent, classPropertyParent)
                } else {
                    visitDeclaration(element, kind, parent, classPropertyParent)
                }
            }
            else -> visitChildren(element, parent, classPropertyParent)
        }
    }

    private fun visitCall(
        call: KtCallExpression,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        if (includeCalls) {
            val callee = call.calleeExpression
                ?: return qualify(SourceReadLimitation.UNSUPPORTED_ENTITY)
            val name = (callee as? KtNameReferenceExpression)?.referencedName()
            val entityName = name?.let(SourceEntityName::present)?.let { admitted ->
                when (admitted) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> null
                }
            } ?: SourceEntityName.Unavailable
            val callSelector = issueEntitySelector(
                call.textRange,
                SourceEntityKind.CALL,
                entityName,
                parent.selector,
            ) ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val calleeSelector = issueEntitySelector(
                callee.textRange,
                SourceEntityKind.CALLEE,
                entityName,
                callSelector,
            ) ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val entity = when (
                val created = SourceEntity.Call.create(
                    callSelector,
                    sourceNestingDepth(parent.depth),
                    calleeSelector,
                    (callee as? KtNameReferenceExpression)?.let(target)
                        ?: SourceEntityTarget.Unresolved(
                            CompilerUnresolvedReason.UNSUPPORTED_TARGET,
                        ),
                )
            ) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected ->
                    return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            entities += entity
        }
        if (!stopped()) visitChildren(call, parent, classPropertyParent)
    }

    private fun visitReference(
        reference: KtNameReferenceExpression,
        parent: NativeStructuralParent,
    ) {
        if (!includeReferences || reference.isCallCallee()) return
        val name = reference.referencedName()
            ?: return qualify(SourceReadLimitation.UNSUPPORTED_ENTITY)
        val selector = issueEntitySelector(
            reference.textRange,
            SourceEntityKind.REFERENCE,
            name,
            parent.selector,
        ) ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        val entity = when (
            val created = SourceEntity.Reference.create(
                selector,
                sourceNestingDepth(parent.depth),
                target(reference),
            )
        ) {
            is Refinement.Refined -> created.value
            is Refinement.Rejected ->
                return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        }
        entities += entity
    }

    private fun visitDeclaration(
        declaration: KtNamedDeclaration,
        kind: DeclarationKind,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        val name = declaration.sourceEntityName(kind)
            ?: return qualify(SourceReadLimitation.UNSUPPORTED_ENTITY)
        val selector = issueEntitySelector(declaration.textRange, kind.entityKind(), name, parent.selector)
            ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        if (includeDeclarations) {
            val semanticVisibility = visibility(declaration, NativeVisibilityTarget.DECLARATION)
                ?: return qualify(SourceReadLimitation.SEMANTIC_RESOLUTION_INCOMPLETE)
            val candidate = declaration.candidateSelector(document.snapshot, kind, name)
                ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val entity = when (
                val created = SourceEntity.Declaration.create(
                    selector,
                    sourceNestingDepth(parent.depth),
                    kind,
                    semanticVisibility,
                    DeclarationSemanticIdentity.Candidate(candidate),
                )
            ) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected ->
                    return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            entities += entity
        }
        val childParent = NativeStructuralParent(selector, parent.depth + 1)
        val classParent = if (declaration is KtClassOrObject) {
            childParent
        } else {
            classPropertyParent
        }
        visitChildren(declaration, childParent, classParent)
    }

    private fun visitParameter(
        parameter: KtParameter,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        if (!parameter.isSupportedValueParameter()) return
        val name = parameter.name ?: return qualify(SourceReadLimitation.UNSUPPORTED_ENTITY)
        if (
            includeDeclarations &&
            parameter.hasValOrVar() &&
            parameter.ownerDeclaration is KtPrimaryConstructor
        ) {
            val propertyParent = classPropertyParent
                ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val propertySelector = issueEntitySelector(
                parameter.textRange,
                SourceEntityKind.DECLARATION_PROPERTY,
                name,
                propertyParent.selector,
            ) ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val semanticVisibility = visibility(
                parameter,
                NativeVisibilityTarget.PRIMARY_CONSTRUCTOR_PROPERTY,
            ) ?: return qualify(SourceReadLimitation.SEMANTIC_RESOLUTION_INCOMPLETE)
            val candidate = parameter.candidateSelector(
                document.snapshot,
                DeclarationKind.PROPERTY,
                name,
            )
                ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            val property = when (
                val created = SourceEntity.Declaration.create(
                    propertySelector,
                    sourceNestingDepth(propertyParent.depth),
                    DeclarationKind.PROPERTY,
                    semanticVisibility,
                    DeclarationSemanticIdentity.Candidate(candidate),
                )
            ) {
                is Refinement.Refined -> created.value
                is Refinement.Rejected ->
                    return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            entities += property
        }
        if (!includeParameters) return
        val selector = issueEntitySelector(
            parameter.textRange,
            SourceEntityKind.VALUE_PARAMETER,
            name,
            parent.selector,
        ) ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        val entity = when (
            val created = SourceEntity.ValueParameter.create(
                selector,
                sourceNestingDepth(parent.depth),
            )
        ) {
            is Refinement.Refined -> created.value
            is Refinement.Rejected ->
                return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        }
        entities += entity
    }

    private fun visitAnonymousCallable(
        body: KtExpression?,
        parent: NativeStructuralParent,
        classPropertyParent: NativeStructuralParent?,
    ) {
        if (body == null || !region.range.contains(body.textRange)) return
        val range = document.snapshot.sourceRange(body.textRange)
            ?: return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        val selector = when (
            val issued = SourceSelector.issueNested(
                parent.selector,
                range,
                SourceRegionKind.CALLABLE_BODY,
            )
        ) {
            is Refinement.Refined -> issued.value
            is Refinement.Rejected ->
                return reject(IntellijSourceReadRejection.CONTRACT_VIOLATION)
        }
        visitChildren(
            body,
            NativeStructuralParent(selector, parent.depth + 1),
            classPropertyParent,
        )
    }

    private fun issueEntitySelector(
        textRange: TextRange,
        kind: SourceEntityKind,
        name: String,
        parent: SourceSelector,
    ): SourceSelector.Entity? = when (val admitted = SourceEntityName.present(name)) {
        is Refinement.Refined -> issueEntitySelector(textRange, kind, admitted.value, parent)
        is Refinement.Rejected -> null
    }

    private fun issueEntitySelector(
        textRange: TextRange,
        kind: SourceEntityKind,
        name: SourceEntityName,
        parent: SourceSelector,
    ): SourceSelector.Entity? {
        val range = document.snapshot.sourceRange(textRange) ?: return null
        val nonEmpty = when (val admitted = NonEmptySourceRange.create(range)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
        return when (val issued = SourceSelector.issueEntity(parent, nonEmpty, kind, name)) {
            is Refinement.Refined -> issued.value
            is Refinement.Rejected -> null
        }
    }

    private fun examine(): Boolean {
        if (examined == MAX_NATIVE_SOURCE_ENTITY_WORK) {
            limitation = SourceReadLimitation.WORK_LIMIT_REACHED
            return false
        }
        examined += 1
        return true
    }

    private fun stopped(): Boolean = limitation != null || rejection != null

    private fun qualify(value: SourceReadLimitation) {
        limitation = value
    }

    private fun reject(value: IntellijSourceReadRejection) {
        rejection = value
    }
}

private fun IntellijSourceEntityPage.withLimitation(
    limitation: SourceReadLimitation,
): IntellijSourceEntityPage = when (this) {
    is IntellijSourceEntityPage.Complete -> copy(limitations = limitations + limitation)
    is IntellijSourceEntityPage.Prefix -> copy(limitations = limitations + limitation)
    is IntellijSourceEntityPage.Rejected -> this
}

private fun KtNamedDeclaration.sourceDeclarationKind(): DeclarationKind? = when (this) {
    is KtClassOrObject -> DeclarationKind.CLASSLIKE
    is KtConstructor<*> -> DeclarationKind.CONSTRUCTOR
    is KtNamedFunction -> DeclarationKind.FUNCTION
    is KtProperty -> DeclarationKind.PROPERTY
    is KtTypeAlias -> DeclarationKind.TYPE_ALIAS
    else -> null
}

private fun KtNamedDeclaration.sourceEntityName(kind: DeclarationKind): String? =
    name ?: if (kind == DeclarationKind.CONSTRUCTOR) {
        (this as? KtConstructor<*>)?.getContainingClassOrObject()?.name
    } else {
        null
    }

private fun DeclarationKind.entityKind(): SourceEntityKind = when (this) {
    DeclarationKind.CLASSLIKE -> SourceEntityKind.DECLARATION_CLASSLIKE
    DeclarationKind.CONSTRUCTOR -> SourceEntityKind.DECLARATION_CONSTRUCTOR
    DeclarationKind.FUNCTION -> SourceEntityKind.DECLARATION_FUNCTION
    DeclarationKind.PROPERTY -> SourceEntityKind.DECLARATION_PROPERTY
    DeclarationKind.TYPE_ALIAS -> SourceEntityKind.DECLARATION_TYPE_ALIAS
}

private fun KtParameter.isSupportedValueParameter(): Boolean =
    !isLoopParameter &&
        !isCatchParameter &&
        !isContextParameter &&
        !isFunctionTypeParameter &&
        ownerDeclaration is KtFunction

private fun KtNameReferenceExpression.referencedName(): String? =
    getReferencedName().takeIf { it.isNotBlank() }

private fun KtNameReferenceExpression.isCallCallee(): Boolean =
    (parent as? KtCallExpression)?.calleeExpression === this

private fun KtNamedDeclaration.candidateSelector(
    snapshot: SourceSnapshot,
    kind: DeclarationKind,
    name: String,
): CandidateSelector.Declaration? {
    val file = containingFile?.virtualFile ?: return null
    val nativePath = if (file.fileSystem.protocol == "file") {
        runCatching { Path.of(file.path).toAbsolutePath().normalize() }.getOrNull()
    } else {
        null
    } ?: return null
    val candidate = when (
        val created = SymbolDiscoveryCandidate.fromBoundary(
            if (kind == DeclarationKind.CLASSLIKE) {
                SymbolDiscoveryKind.CLASS
            } else {
                SymbolDiscoveryKind.SYMBOL
            },
            name,
            snapshot.lease,
            nativePath,
            file.url,
            textRange.startOffset,
        )
    ) {
        is Refinement.Refined -> created.value
        is Refinement.Rejected -> return null
    }
    val candidateFile = (candidate.location as? SymbolDiscoveryCandidateLocation.Declaration)
        ?.file as? SymbolDiscoveryFileIdentity.Workspace
        ?: return null
    val scope = SymbolSearchScope.ExactFile(
        candidateFile.path,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.INCLUDE,
    )
    val selection = when (
        val restored = SymbolDiscoverySelection.restore(snapshot.lease, scope, candidate)
    ) {
        is Refinement.Refined -> restored.value
        is Refinement.Rejected -> return null
    }
    return when (val issued = CandidateSelector.declaration(selection)) {
        is Refinement.Refined -> issued.value
        is Refinement.Rejected -> null
    }
}

private fun KaSymbolVisibility.sourceVisibility(): DeclarationVisibility? = when (this) {
    KaSymbolVisibility.PUBLIC -> DeclarationVisibility.PUBLIC
    KaSymbolVisibility.PROTECTED -> DeclarationVisibility.PROTECTED
    KaSymbolVisibility.INTERNAL -> DeclarationVisibility.INTERNAL
    KaSymbolVisibility.PRIVATE -> DeclarationVisibility.PRIVATE
    KaSymbolVisibility.LOCAL -> DeclarationVisibility.LOCAL
    KaSymbolVisibility.PACKAGE_PROTECTED,
    KaSymbolVisibility.PACKAGE_PRIVATE,
    KaSymbolVisibility.UNKNOWN,
        -> null
}

private fun sourceNestingDepth(raw: Int): SourceNestingDepth = when (
    val parsed = SourceNestingDepth.parse(raw)
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("Non-negative structural depth must refine")
}

private class LiveIntellijSourceReadAccess(
    private val project: Project,
) : IntellijSourceReadAccess {
    override suspend fun capture(
        context: SourceReadContext,
        selector: SymbolSelector,
    ): IntellijSourceReadAccessResult {
        if (selector.lease.workspaceRoot != context.lease.workspaceRoot) {
            return rejected(IntellijSourceReadRejection.WORKSPACE_ROOT_MISMATCH)
        }
        if (selector.lease.generation != context.lease.generation) {
            return rejected(IntellijSourceReadRejection.STALE_GENERATION)
        }
        if (project.isDisposed) return rejected(IntellijSourceReadRejection.SOURCE_UNAVAILABLE)
        if (DumbService.isDumb(project)) {
            return rejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        }
        return try {
            readAction {
                captureInReadAction(context, selector)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IndexNotReadyException) {
            rejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        } catch (_: RuntimeException) {
            rejected(IntellijSourceReadRejection.PROVIDER_FAILURE)
        }
    }

    private fun captureInReadAction(
        context: SourceReadContext,
        selector: SymbolSelector,
    ): IntellijSourceReadAccessResult {
        if (project.isDisposed) return rejected(IntellijSourceReadRejection.SOURCE_UNAVAILABLE)
        if (DumbService.isDumb(project)) {
            return rejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        }
        val fileIdentity = selector.file as? SymbolDiscoveryFileIdentity.Workspace
            ?: return rejected(IntellijSourceReadRejection.OUTSIDE_SOURCE_SCOPE)
        val file = VirtualFileManager.getInstance().findFileByNioPath(Path.of(fileIdentity.path.value))
            ?.takeIf { it.isValid }
            ?: return rejected(IntellijSourceReadRejection.SOURCE_UNAVAILABLE)
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file)
            ?: return rejected(IntellijSourceReadRejection.SOURCE_UNAVAILABLE)
        if (documents.isFileModified(file)) {
            return rejected(IntellijSourceReadRejection.DOCUMENT_DIRTY)
        }
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
            return rejected(IntellijSourceReadRejection.PSI_DOCUMENT_UNCOMMITTED)
        }
        val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile
            ?: return rejected(IntellijSourceReadRejection.ANCHOR_NOT_FOUND)
        val declaration = exactDeclaration(psiFile, selector)
            ?: return rejected(IntellijSourceReadRejection.ANCHOR_NOT_FOUND)
        val evidence = declaration.compilerEvidence(selector)
            ?: return rejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        val revalidated = when (val proof = RevalidatedSymbolSelector.validate(selector, evidence)) {
            is Refinement.Refined -> proof.value
            is Refinement.Rejected ->
                return rejected(IntellijSourceReadRejection.DECLARATION_MOVED_OR_CHANGED)
        }
        if (DumbService.isDumb(project)) {
            return rejected(IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE)
        }
        return when (
            val capture = IntellijCommittedSourceCapture.create(
                context,
                revalidated,
                document.charsSequence.toString(),
            )
        ) {
            is Refinement.Refined -> IntellijSourceReadAccessResult.Captured(capture.value)
            is Refinement.Rejected -> rejected(capture.failure)
        }
    }
}

private fun exactDeclaration(
    file: KtFile,
    selector: SymbolSelector,
): KtNamedDeclaration? {
    val leaf = file.findElementAt(selector.range.startInclusive) ?: return null
    val matches = ArrayList<KtNamedDeclaration>()
    var current: PsiElement? = leaf
    while (current != null) {
        val declaration = current as? KtNamedDeclaration
        if (
            declaration != null &&
            declaration.name == selector.name.value &&
            declaration.textRange.startOffset == selector.range.startInclusive &&
            declaration.textRange.endOffset == selector.range.endExclusive
        ) {
            matches += declaration
        }
        current = current.parent
    }
    return matches.distinct().singleOrNull()
}

private sealed interface CandidateDeclarationLookup {
    data class Found(val declaration: KtNamedDeclaration) : CandidateDeclarationLookup
    data object Missing : CandidateDeclarationLookup
    data object Ambiguous : CandidateDeclarationLookup
}

/** Revalidates only the weak discovery facts; it deliberately does not acquire K2 authority. */
private fun exactCandidateDeclaration(
    file: KtFile,
    selector: CandidateSelector.Declaration,
): CandidateDeclarationLookup {
    val candidate = selector.selection.candidate
    val location = candidate.location as? SymbolDiscoveryCandidateLocation.Declaration
        ?: return CandidateDeclarationLookup.Missing
    val leaf = file.findElementAt(location.offset.value) ?: return CandidateDeclarationLookup.Missing
    val matches = ArrayList<KtNamedDeclaration>()
    var current: PsiElement? = leaf
    while (current != null) {
        val declaration = current as? KtNamedDeclaration
        if (
            declaration != null &&
            declaration.name == candidate.name.value &&
            declaration.textRange.startOffset == location.offset.value &&
            declaration.matches(candidate.kind)
        ) {
            matches += declaration
        }
        current = current.parent
    }
    val distinct = matches.distinct()
    return when {
        distinct.isEmpty() -> CandidateDeclarationLookup.Missing
        distinct.size == 1 -> CandidateDeclarationLookup.Found(distinct.single())
        else -> CandidateDeclarationLookup.Ambiguous
    }
}

private fun KtNamedDeclaration.matches(kind: io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind):
    Boolean = when (kind) {
    io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind.CLASS -> this is KtClassOrObject
    io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind.SYMBOL -> this !is KtClassOrObject
    io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind.FILE,
    io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind.TEXT,
        -> false
}

private fun CandidateSelector.workspaceFile(): SymbolDiscoveryFileIdentity.Workspace? = when (this) {
    is CandidateSelector.Declaration ->
        selection.candidate.location.file as? SymbolDiscoveryFileIdentity.Workspace
    is CandidateSelector.File -> file
    is CandidateSelector.Range -> file
}

private fun KaSymbol.sourceEntityTarget(document: LiveSourceDocument): SourceEntityTarget {
    val declaration = psi as? KtNamedDeclaration
        ?: return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    val kind = declaration.sourceDeclarationKind()
        ?: return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    val name = declaration.sourceEntityName(kind)
        ?: return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    val candidate = declaration.candidateSelector(document.snapshot, kind, name)
        ?: return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    return SourceEntityTarget.Candidate(candidate)
}

private fun KtNamedDeclaration.localSourceTarget(
    document: LiveSourceDocument,
): SourceEntityTarget {
    if (containingFile?.virtualFile?.url != document.psiFile.virtualFile?.url) {
        return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    }
    val range = document.snapshot.sourceRange(textRange)
        ?: return SourceEntityTarget.Unresolved(CompilerUnresolvedReason.UNSUPPORTED_TARGET)
    return SourceEntityTarget.Local(SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION))
}

private fun KtNamedDeclaration.compilerEvidence(
    selector: SymbolSelector,
): CompilerGroundedSymbolEvidence? {
    val projection = when (val result = analyze(this) { symbol.sourceProjection() }) {
        is SourceCompilerProjectionResult.Projected -> result.projection
        SourceCompilerProjectionResult.Rejected -> return null
    }
    return when (
        val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file = selector.file,
            rawStartInclusive = textRange.startOffset,
            rawEndExclusive = textRange.endOffset,
            rawName = name.orEmpty(),
            rawQualifiedIdentity = projection.qualifiedIdentity,
            kind = projection.kind,
            signature = projection.signature,
        )
    ) {
        is Refinement.Refined -> evidence.value
        is Refinement.Rejected -> null
    }
}

private data class SourceCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private sealed interface SourceCompilerProjectionResult {
    data class Projected(val projection: SourceCompilerProjection) : SourceCompilerProjectionResult
    data object Rejected : SourceCompilerProjectionResult
}

private fun KaSymbol.sourceProjection(): SourceCompilerProjectionResult = when (this) {
    is KaConstructorSymbol -> {
        val identity = containingClassId?.asSingleFqName()?.asString()?.let { "$it.<init>" }
            ?: return SourceCompilerProjectionResult.Rejected
        projected(CompilerSymbolKind.CONSTRUCTOR, identity, sourceFunctionSignature(identity))
    }
    is KaFunctionSymbol -> {
        val identity = callableId?.asSingleFqName()?.asString()
            ?: return SourceCompilerProjectionResult.Rejected
        projected(CompilerSymbolKind.FUNCTION, identity, sourceFunctionSignature(identity))
    }
    is KaKotlinPropertySymbol -> {
        val identity = callableId?.asSingleFqName()?.asString()
            ?: return SourceCompilerProjectionResult.Rejected
        projected(
            CompilerSymbolKind.PROPERTY,
            identity,
            CanonicalCompilerSignature.property(
                identity,
                receiverParameter?.returnType?.toString(),
                contextReceivers.map { it.type.toString() },
                returnType.toString(),
            ),
        )
    }
    is KaTypeAliasSymbol -> {
        val identity = classId?.asSingleFqName()?.asString()
            ?: return SourceCompilerProjectionResult.Rejected
        projected(
            CompilerSymbolKind.TYPE_ALIAS,
            identity,
            CanonicalCompilerSignature.typeAlias(identity),
        )
    }
    is KaClassLikeSymbol -> {
        val identity = classId?.asSingleFqName()?.asString()
            ?: return SourceCompilerProjectionResult.Rejected
        projected(
            CompilerSymbolKind.CLASSLIKE,
            identity,
            CanonicalCompilerSignature.classLike(identity),
        )
    }
    else -> SourceCompilerProjectionResult.Rejected
}

private fun KaFunctionSymbol.sourceFunctionSignature(
    identity: String,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        identity,
        receiverParameter?.returnType?.toString(),
        contextReceivers.map { it.type.toString() },
        valueParameters.map { it.returnType.toString() },
        (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun projected(
    kind: CompilerSymbolKind,
    identity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): SourceCompilerProjectionResult = when (signature) {
    is Refinement.Refined -> SourceCompilerProjectionResult.Projected(
        SourceCompilerProjection(kind, identity, signature.value),
    )
    is Refinement.Rejected -> SourceCompilerProjectionResult.Rejected
}

private fun exactProject(root: CanonicalSemanticProjectRoot): Project? =
    ProjectManager.getInstance().openProjects.singleOrNull { project ->
        !project.isDisposed && project.basePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?.toString() == root.value
    }

private fun rejected(
    reason: IntellijSourceReadRejection,
): IntellijSourceReadAccessResult.Rejected = IntellijSourceReadAccessResult.Rejected(reason)

private fun regionRejected(
    reason: IntellijSourceReadRejection,
): IntellijSourceRegionAccessResult.Rejected =
    IntellijSourceRegionAccessResult.Rejected(reason)
