package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.Processor
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.NativeRelationFamily
import io.github.amichne.kast.symbol.contract.NativeRelationLimitation
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.RevalidatedExactDeclaration

internal data class IntellijPsiRelationEvent(
    val related: PsiNamedElement,
    val occurrenceFile: VirtualFile,
    val occurrenceStartInclusive: Int,
    val occurrenceEndExclusive: Int,
) : IntellijNativeRelationEvent

private enum class IntellijRelationStreamState {
    STREAMING,
    HALTED,
}

private sealed interface IntellijNamedRelationTarget {
    data class Found(
        val declaration: PsiNamedElement,
    ) : IntellijNamedRelationTarget

    data object Unsupported : IntellijNamedRelationTarget
}

private sealed interface IntellijResolvedRelationTarget {
    data class Found(
        val declaration: PsiNamedElement,
        val file: VirtualFile,
    ) : IntellijResolvedRelationTarget

    data object Unresolved : IntellijResolvedRelationTarget
}

private enum class IntellijReferenceSubjectResolution {
    MATCHES,
    MISMATCH,
}

enum class IntellijInvocationReferenceAdmission {
    INVOCATION,
    NON_INVOCATION,
    UNSUPPORTED,
}

enum class IntellijNestedRelationTraversal {
    DESCEND,
    SKIP_NESTED_DECLARATION,
}

interface IntellijRelationSemanticPolicy {
    /**
     * Proof transition: `PsiReference -> IntellijInvocationReferenceAdmission`.
     *
     * Establishes whether one resolved reference is an actual invocation, a proven non-call use,
     * or an unsupported language shape. Live PSI remains inside the request-local relation read.
     */
    fun invocation(reference: PsiReference): IntellijInvocationReferenceAdmission

    /**
     * Proof transition: `PsiNamedElement + PsiElement -> IntellijNestedRelationTraversal`.
     *
     * Establishes whether traversal remains in the selected subject's executable body or has
     * crossed a nested declaration boundary whose references belong to another callable/type.
     */
    fun nestedTraversal(
        subject: PsiNamedElement,
        element: PsiElement,
    ): IntellijNestedRelationTraversal
}

internal class IntellijPsiNativeRelationSearch(
    private val project: com.intellij.openapi.project.Project,
    private val semanticPolicy: IntellijRelationSemanticPolicy,
    private val exactLookup: IntellijPsiExactDeclarationLookup =
        IntellijPsiExactDeclarationLookup(project),
) : IntellijNativeRelationSearch {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + NativeRelationRequest + bounded event consumer to
     * IntellijNativeRelationSearchResult.
     *
     * Establishes unchanged exact subject identity, then streams one-hop references/callers through
     * [ReferencesSearch], implementations/inheritors/overrides through
     * [DefinitionsScopedSearch] with deep traversal disabled, or outgoing callees through bounded
     * PSI reference walking. Every occurrence and related target is scope-checked before projection.
     * Unresolved targets qualify coverage. Live PSI, queries, and files remain request-local.
     */
    override fun search(
        compiledScope: CompiledIntellijSearchScope,
        request: NativeRelationRequest,
        consumer: (IntellijNativeRelationEvent) -> Boolean,
    ): IntellijNativeRelationSearchResult {
        val subject = when (
            val live = exactLookup.findLive(compiledScope, request.selector.lookupKey())
        ) {
            is IntellijLiveExactDeclarationLookupResult.Found -> {
                when (
                    RevalidatedExactDeclaration.validate(
                        request.selector,
                        live.evidence,
                    )
                ) {
                    is Refinement.Refined -> live.declaration
                    is Refinement.Rejected ->
                        return rejected(
                            IntellijNativeRelationSearchRejection.SELECTOR_CHANGED,
                        )
                }
            }
            is IntellijLiveExactDeclarationLookupResult.Rejected ->
                return rejected(live.reason.relationRejection())
        }
        val searchState = RelationSearchState(
            compiledScope = compiledScope,
            subject = subject,
            consumer = consumer,
        )
        return when (request.family) {
            NativeRelationFamily.REFERENCES,
                -> searchState.references(invocationsOnly = false)
            NativeRelationFamily.CALLERS -> searchState.references(invocationsOnly = true)
            NativeRelationFamily.IMPLEMENTATIONS,
            NativeRelationFamily.INHERITORS,
            NativeRelationFamily.OVERRIDES,
                -> searchState.definitions()
            NativeRelationFamily.CALLEES -> searchState.callees()
        }
    }

    private inner class RelationSearchState(
        private val compiledScope: CompiledIntellijSearchScope,
        private val subject: PsiNamedElement,
        private val consumer: (IntellijNativeRelationEvent) -> Boolean,
    ) {
        private val limitations = linkedSetOf<NativeRelationLimitation>()
        private var state = IntellijRelationStreamState.STREAMING

        fun references(invocationsOnly: Boolean): IntellijNativeRelationSearchResult {
            val terminal = ReferencesSearch.search(
                subject,
                compiledScope.nativeScope,
                false,
            ).forEach(Processor { reference ->
                ProgressManager.checkCanceled()
                when (reference.subjectResolution(subject)) {
                    IntellijReferenceSubjectResolution.MISMATCH -> {
                        limitations += NativeRelationLimitation.UNRESOLVED_TARGET
                        true
                    }
                    IntellijReferenceSubjectResolution.MATCHES -> when (
                        val admission = if (invocationsOnly) {
                            semanticPolicy.invocation(reference)
                        } else {
                            IntellijInvocationReferenceAdmission.INVOCATION
                        }
                    ) {
                        IntellijInvocationReferenceAdmission.NON_INVOCATION -> true
                        IntellijInvocationReferenceAdmission.UNSUPPORTED -> {
                            limitations += NativeRelationLimitation.UNSUPPORTED_ITEM
                            true
                        }
                        IntellijInvocationReferenceAdmission.INVOCATION -> when (
                            val related = reference.element.nearestNamedDeclaration()
                        ) {
                            IntellijNamedRelationTarget.Unsupported -> {
                                limitations += NativeRelationLimitation.UNSUPPORTED_ITEM
                                true
                            }
                            is IntellijNamedRelationTarget.Found ->
                                emit(reference, related.declaration)
                        }
                    }
                }
            })
            state = if (terminal) {
                IntellijRelationStreamState.STREAMING
            } else {
                IntellijRelationStreamState.HALTED
            }
            return report()
        }

        fun definitions(): IntellijNativeRelationSearchResult {
            val terminal = DefinitionsScopedSearch.search(
                subject,
                compiledScope.nativeScope,
                false,
            ).forEach(Processor { definition ->
                ProgressManager.checkCanceled()
                val related = definition as? PsiNamedElement
                val file = PsiUtilCore.getVirtualFile(definition)
                when {
                    related == null || file == null -> {
                        limitations += NativeRelationLimitation.UNSUPPORTED_ITEM
                        true
                    }
                    !compiledScope.nativeScope.contains(file) -> true
                    PsiManager.getInstance(project).areElementsEquivalent(subject, definition) -> true
                    else -> emit(
                        IntellijPsiRelationEvent(
                            related = related,
                            occurrenceFile = file,
                            occurrenceStartInclusive = definition.textRange.startOffset,
                            occurrenceEndExclusive = definition.textRange.endOffset,
                        ),
                    )
                }
            })
            state = if (terminal) {
                IntellijRelationStreamState.STREAMING
            } else {
                IntellijRelationStreamState.HALTED
            }
            return report()
        }

        fun callees(): IntellijNativeRelationSearchResult {
            (subject as PsiElement).accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (state == IntellijRelationStreamState.HALTED) {
                            stopWalking()
                            return
                        }
                        ProgressManager.checkCanceled()
                        if (
                            element !== subject &&
                            semanticPolicy.nestedTraversal(subject, element) ==
                            IntellijNestedRelationTraversal.SKIP_NESTED_DECLARATION
                        ) {
                            return
                        }
                        element.references.forEach { reference ->
                            if (state == IntellijRelationStreamState.HALTED) {
                                return@forEach
                            }
                            when (semanticPolicy.invocation(reference)) {
                                IntellijInvocationReferenceAdmission.NON_INVOCATION -> Unit
                                IntellijInvocationReferenceAdmission.UNSUPPORTED ->
                                    limitations += NativeRelationLimitation.UNSUPPORTED_ITEM
                                IntellijInvocationReferenceAdmission.INVOCATION ->
                                    when (val related = reference.resolvedNamedTarget()) {
                                        IntellijResolvedRelationTarget.Unresolved ->
                                            limitations +=
                                                NativeRelationLimitation.UNRESOLVED_TARGET
                                        is IntellijResolvedRelationTarget.Found -> {
                                            if (compiledScope.nativeScope.contains(related.file) &&
                                                !emit(reference, related.declaration)
                                            ) {
                                                stopWalking()
                                            }
                                        }
                                    }
                            }
                        }
                        if (state == IntellijRelationStreamState.STREAMING) {
                            super.visitElement(element)
                        }
                    }
                },
            )
            return report()
        }

        private fun emit(
            reference: PsiReference,
            related: PsiNamedElement,
        ): Boolean {
            val occurrenceFile = PsiUtilCore.getVirtualFile(reference.element)
            if (occurrenceFile == null) {
                limitations += NativeRelationLimitation.UNSUPPORTED_ITEM
                return true
            }
            if (!compiledScope.nativeScope.contains(occurrenceFile)) {
                return true
            }
            val elementStart = reference.element.textRange.startOffset
            return emit(
                IntellijPsiRelationEvent(
                    related = related,
                    occurrenceFile = occurrenceFile,
                    occurrenceStartInclusive = elementStart + reference.rangeInElement.startOffset,
                    occurrenceEndExclusive = elementStart + reference.rangeInElement.endOffset,
                ),
            )
        }

        private fun emit(event: IntellijPsiRelationEvent): Boolean =
            consumer(event).also { keepGoing ->
                if (!keepGoing) {
                    state = IntellijRelationStreamState.HALTED
                }
            }

        private fun report(): IntellijNativeRelationSearchResult =
            when (state) {
                IntellijRelationStreamState.STREAMING ->
                    IntellijNativeRelationSearchResult.Terminal(limitations)
                IntellijRelationStreamState.HALTED ->
                    IntellijNativeRelationSearchResult.Halted(limitations)
            }
    }

    /**
     * Proof transition: `PsiReference + PsiNamedElement -> IntellijReferenceSubjectResolution`.
     *
     * Establishes whether the native reference resolves to the exact request-local subject under
     * IntelliJ element equivalence. Resolution absence and mismatches remain a closed state.
     */
    private fun PsiReference.subjectResolution(
        subject: PsiNamedElement,
    ): IntellijReferenceSubjectResolution =
        if (
            resolve()?.let {
                PsiManager.getInstance(project).areElementsEquivalent(it, subject)
            } == true
        ) {
            IntellijReferenceSubjectResolution.MATCHES
        } else {
            IntellijReferenceSubjectResolution.MISMATCH
        }
}

/**
 * Proof transition: `PsiElement -> IntellijNamedRelationTarget`.
 *
 * Establishes the nearest named PSI declaration containing the element. Absence is represented by
 * the closed unsupported state; callers do not retain a nullable platform value.
 */
private fun PsiElement.nearestNamedDeclaration(): IntellijNamedRelationTarget =
    generateSequence(this as PsiElement?) { it.parent }
        .filterIsInstance<PsiNamedElement>()
        .firstOrNull()
        ?.let(IntellijNamedRelationTarget::Found)
    ?: IntellijNamedRelationTarget.Unsupported

/**
 * Proof transition: `PsiReference -> IntellijResolvedRelationTarget`.
 *
 * Establishes a named resolved declaration with a concrete virtual file. Missing resolution,
 * unnamed declarations, and fileless declarations collapse to the closed unresolved state.
 */
private fun PsiReference.resolvedNamedTarget(): IntellijResolvedRelationTarget =
    when (val named = resolve()?.nearestNamedDeclaration()) {
        is IntellijNamedRelationTarget.Found -> {
            val file = PsiUtilCore.getVirtualFile(named.declaration)
            if (file == null) {
                IntellijResolvedRelationTarget.Unresolved
            } else {
                IntellijResolvedRelationTarget.Found(named.declaration, file)
            }
        }
        IntellijNamedRelationTarget.Unsupported -> IntellijResolvedRelationTarget.Unresolved
        null -> IntellijResolvedRelationTarget.Unresolved
    }

/**
 * Proof transition:
 * `IntellijExactDeclarationLookupRejection -> IntellijNativeRelationSearchRejection`.
 *
 * Preserves every closed exact-lookup failure while translating it to the native relation boundary.
 */
private fun IntellijExactDeclarationLookupRejection.relationRejection():
    IntellijNativeRelationSearchRejection = when (this) {
    IntellijExactDeclarationLookupRejection.STALE_LOCATION ->
        IntellijNativeRelationSearchRejection.STALE_SELECTOR
    IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE ->
        IntellijNativeRelationSearchRejection.OUTSIDE_SCOPE
    IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION ->
        IntellijNativeRelationSearchRejection.AMBIGUOUS_SUBJECT
    IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION ->
        IntellijNativeRelationSearchRejection.UNSUPPORTED_SUBJECT
}

/**
 * Proof transition:
 * `IntellijNativeRelationSearchRejection -> IntellijNativeRelationSearchResult.Rejected`.
 *
 * Wraps a closed native-search rejection without manufacturing terminal or partial coverage.
 */
private fun rejected(
    reason: IntellijNativeRelationSearchRejection,
): IntellijNativeRelationSearchResult.Rejected =
    IntellijNativeRelationSearchResult.Rejected(reason)
