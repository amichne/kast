@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.localIdentityAdmitted
import io.github.amichne.kast.workspace.intellij.read.localIdentityRejected
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.javaInterop.callableSymbol
import org.jetbrains.kotlin.analysis.api.javaInterop.namedClassSymbol
import org.jetbrains.kotlin.analysis.api.projectStructure.kaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Request-local exact lookup and K2 projection for relation subjects and endpoints. */
internal class IntellijK2RelationProjection(
    private val project: com.intellij.openapi.project.Project,
    private val workspaceRoot: CanonicalWorkspaceRoot,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    fun callOwner(lexical: ContainingDeclaration): Refinement<ContainingDeclaration.Found, CallOwnershipFailure> =
        refineCallOwnership(lexical, observation)

    /**
     * Proof transition: `(CompiledRelationScope, RelationEndpoint) -> IntellijRelationSubjectLookup`.
     *
     * A found result establishes exact file/range/name PSI lookup plus identical K2 compiler evidence for the endpoint.
     * [IntellijRelationSubjectFailure] is the closed expected failure. Live VFS, PSI, and K2 values remain inside this
     * request-local adapter.
     */
    fun subject(
        scope: CompiledRelationScope,
        subject: RelationEndpoint,
    ): IntellijRelationSubjectLookup {
        val file =
            when (val identity = subject.file) {
                is SymbolDiscoveryFileIdentity.Workspace ->
                    LocalFileSystem.getInstance().findFileByNioFile(Path.of(identity.path.value))
                is SymbolDiscoveryFileIdentity.External ->
                    VirtualFileManager.getInstance().findFileByUrl(identity.url.value)
            } ?: return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
        if (!scope.nativeScope.contains(file)) {
            return rejected(IntellijRelationSubjectFailure.OUTSIDE_SCOPE)
        }
        val psiFile =
            PsiManager.getInstance(project).findFile(file)
                ?: return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
        when (subject.constraints.packageName.admitPackage { psiFile.relationPackageEvidence() }) {
            IntellijRelationPackageAdmission.ADMITTED -> Unit
            IntellijRelationPackageAdmission.OUTSIDE_SCOPE ->
                return rejected(IntellijRelationSubjectFailure.OUTSIDE_SCOPE)
            IntellijRelationPackageAdmission.UNSUPPORTED ->
                return rejected(IntellijRelationSubjectFailure.UNSUPPORTED_SUBJECT)
        }
        val candidates =
            generateSequence(psiFile.findElementAt(subject.range.startInclusive)) {
                    it.parent
                }
                .filterIsInstance<PsiNamedElement>()
                .filter { it is KtNamedDeclaration || it is PsiMember }
                .filter { declaration ->
                    declaration.textRange?.startOffset == subject.range.startInclusive &&
                        declaration.textRange?.endOffset == subject.range.endExclusive &&
                        declaration.name == subject.name.value
                }
                .toList()
        val declaration =
            when (candidates.size) {
                0 -> return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
                1 -> candidates.single()
                else -> return rejected(IntellijRelationSubjectFailure.AMBIGUOUS_SUBJECT)
            }
        val evidence =
            when (val projection = project(declaration)) {
                is IntellijRelationDeclarationProjection.Projected -> projection.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return rejected(IntellijRelationSubjectFailure.COMPILER_IDENTITY_UNAVAILABLE)
            }
        return when (RevalidatedRelationEndpoint.validate(subject, evidence)) {
            is Refinement.Refined -> IntellijRelationSubjectLookup.Found(declaration, evidence)
            is Refinement.Rejected -> rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
        }
    }

    /**
     * Proof transition: `KtNamedDeclaration -> IntellijRelationDeclarationProjection`.
     *
     * A projected result establishes exact detached file/range/name/kind and overload-aware K2 identity. Unsupported
     * files, declarations, or local/unavailable compiler identities remain closed as
     * [IntellijRelationDeclarationProjection.Unsupported]. Live values remain local.
     */
    fun project(declaration: PsiNamedElement): IntellijRelationDeclarationProjection {
        val file = declaration.containingFile?.virtualFile ?: return IntellijRelationDeclarationProjection.Unsupported
        val detached =
            when (val result = detachRelationFile(file, workspaceRoot)) {
                is IntellijDetachedRelationFile.Found -> result.identity
                IntellijDetachedRelationFile.Unsupported -> return IntellijRelationDeclarationProjection.Unsupported
            }
        val projection =
            when (
                val result =
                    analyze(declaration.kaModule(null)) {
                        nativeSymbol(declaration)?.compilerProjection(this, detached, observation = observation)
                            ?: IntellijCompilerProjectionResult.Unsupported
                    }
            ) {
                is IntellijCompilerProjectionResult.Projected -> result.projection
                is IntellijCompilerProjectionResult.LocalRejected -> {
                    observation.localIdentityRejected(result.failure)
                    return IntellijRelationDeclarationProjection.Unsupported
                }
                IntellijCompilerProjectionResult.Unsupported -> return IntellijRelationDeclarationProjection.Unsupported
            }
        val grounded = groundedProjection(declaration, detached, projection)
        if (
            projection.signature.declarationAddress
                is io.github.amichne.kast.symbol.contract.CompilerDeclarationAddress.Local
        ) {
            when (grounded) {
                is IntellijRelationDeclarationProjection.Projected -> observation.localIdentityAdmitted()
                IntellijRelationDeclarationProjection.Unsupported ->
                    observation.localIdentityRejected(
                        io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure.SignatureUnavailable
                    )
            }
        }
        return grounded
    }

    /**
     * Proof transition: `IntellijRelationReferenceAdmission.Admitted -> IntellijK2TargetConfirmation`.
     *
     * Exact-symbol admission preserves compiler identity and exact declaration location. Class-construction admission
     * establishes in one K2 session that the call resolves to a constructor whose containing class has the selected
     * compiler identity and source file/range. Different and unproved targets remain finite non-admission states. Raw
     * PSI and K2 symbols remain request-local.
     */
    fun confirmTarget(admitted: IntellijRelationReferenceAdmission.Admitted): IntellijK2TargetConfirmation =
        when (admitted) {
            is IntellijRelationReferenceAdmission.Admitted.ExactSymbol ->
                confirmExactTarget(admitted.reference, admitted.endpoint)
            is IntellijRelationReferenceAdmission.Admitted.ClassConstruction ->
                confirmClassConstruction(admitted, workspaceRoot, observation)
        }

    fun confirmReferenceTarget(reference: KtReference, subject: RelationEndpoint): IntellijReferenceTargetResult {
        val declaration =
            when (val resolved = resolve(reference)) {
                is IntellijK2ResolvedDeclaration.Found -> resolved.declaration
                IntellijK2ResolvedDeclaration.InvokeReceiver,
                is IntellijK2ResolvedDeclaration.FunctionInvocation,
                is IntellijK2ResolvedDeclaration.ParameterInvocation,
                is IntellijK2ResolvedDeclaration.SourceLess -> return IntellijReferenceTargetResult.Different
                is IntellijK2ResolvedDeclaration.Unsupported,
                IntellijK2ResolvedDeclaration.Unresolved -> return IntellijReferenceTargetResult.Unresolved
            }
        val evidence =
            when (val result = project(declaration)) {
                is IntellijRelationDeclarationProjection.Projected -> result.evidence
                IntellijRelationDeclarationProjection.Unsupported -> return IntellijReferenceTargetResult.Unresolved
            }
        return when (
            val proof =
                io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget.fromCompiler(
                    subject,
                    evidence,
                )
        ) {
            is Refinement.Refined -> IntellijReferenceTargetResult.Confirmed(proof.value)
            is Refinement.Rejected -> IntellijReferenceTargetResult.Different
        }
    }

    private fun confirmExactTarget(reference: KtReference, subject: RelationEndpoint): IntellijK2TargetConfirmation =
        when (confirmReferenceTarget(reference, subject)) {
            is IntellijReferenceTargetResult.Confirmed -> IntellijK2TargetConfirmation.EXACT_SUBJECT
            IntellijReferenceTargetResult.Different -> IntellijK2TargetConfirmation.DIFFERENT_SYMBOL
            IntellijReferenceTargetResult.Unresolved -> IntellijK2TargetConfirmation.UNRESOLVED
        }

    /**
     * Confirms the closed implementation, inheritance, or override meaning through K2 relation APIs; index enumeration
     * alone never admits a definition edge.
     */
    fun confirmDefinition(
        subject: PsiNamedElement,
        candidate: PsiNamedElement,
        relation: IntellijDefinitionRelation,
    ): IntellijK2DefinitionConfirmation =
        analyze(candidate.kaModule(null)) {
            val subjectSymbol = nativeSymbol(subject)
            val candidateSymbol = nativeSymbol(candidate)
            when (relation) {
                IntellijDefinitionRelation.INHERITORS -> {
                    val parent =
                        subjectSymbol as? KaClassSymbol ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                    val child =
                        candidateSymbol as? KaClassSymbol ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                    if (child.isDirectSubClassOf(parent)) confirmed() else different()
                }
                IntellijDefinitionRelation.OVERRIDES -> {
                    val parent =
                        subjectSymbol as? KaCallableSymbol
                            ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                    val child =
                        candidateSymbol as? KaCallableSymbol
                            ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                    if (
                        child.directlyOverriddenSymbols.any {
                            it.compareIdentity(parent, this) == IntellijSymbolIdentityComparison.SAME
                        }
                    ) {
                        confirmed()
                    } else {
                        different()
                    }
                }
                IntellijDefinitionRelation.IMPLEMENTATIONS ->
                    when {
                        subjectSymbol is KaClassSymbol && candidateSymbol is KaClassSymbol ->
                            if (
                                candidateSymbol.modality != KaSymbolModality.ABSTRACT &&
                                    candidateSymbol.isSubClassOf(subjectSymbol)
                            )
                                confirmed()
                            else different()
                        subjectSymbol is KaCallableSymbol && candidateSymbol is KaCallableSymbol ->
                            if (
                                candidateSymbol.modality != KaSymbolModality.ABSTRACT &&
                                    candidateSymbol.allOverriddenSymbols.any {
                                        it.compareIdentity(subjectSymbol, this) == IntellijSymbolIdentityComparison.SAME
                                    }
                            )
                                confirmed()
                            else different()
                        else -> IntellijK2DefinitionConfirmation.UNSUPPORTED
                    }
            }
        }

    /** Resolves one Kotlin call/reference target to a source declaration through K2. */
    fun resolve(reference: KtReference): IntellijK2ResolvedDeclaration =
        analyze(reference.element) {
                val symbol = reference.resolveToSymbol()
                if (symbol == null) {
                    val invocation = resolvedFunctionValueInvocation(reference)
                    if (invocation == null) {
                        IntellijInvokeCallRefinement.UNRESOLVED.observe(observation)
                        return@analyze IntellijK2ResolvedDeclaration.Unresolved
                    }
                    IntellijInvokeCallRefinement.CONFIRMED.observe(observation)
                    return@analyze functionValueInvocation(reference, invocation)
                }
                val parameterInvocation = resolvedParameterInvocation(reference, symbol)
                if (parameterInvocation != null) return@analyze parameterInvocation
                val psi = symbol.psi
                val declaration = psi as? PsiNamedElement
                when {
                    psi == null -> {
                        observation.terminated(IntellijReadTermination.K2_SYMBOL_WITHOUT_PSI)
                        sourceLessCallable(symbol)
                    }
                    declaration == null -> {
                        observation.terminated(IntellijReadTermination.K2_NON_KOTLIN_PSI)
                        IntellijK2ResolvedDeclaration.Unresolved
                    }
                    else -> IntellijK2ResolvedDeclaration.Found(declaration)
                }
            }
            .observedResolutionBy(observation)

    /** Java resolution finds the declaration; K2 then proves its exact retained compiler identity. */
    fun confirmJavaReferenceTarget(reference: PsiReference, subject: RelationEndpoint): IntellijReferenceTargetResult {
        val declaration =
            reference.resolve()?.navigationElement as? PsiNamedElement
                ?: return IntellijReferenceTargetResult.Unresolved
        return when (val result = project(declaration)) {
            IntellijRelationDeclarationProjection.Unsupported -> IntellijReferenceTargetResult.Unresolved
            is IntellijRelationDeclarationProjection.Projected ->
                when (
                    val proof =
                        io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget.fromCompiler(
                            subject,
                            result.evidence,
                        )
                ) {
                    is Refinement.Refined -> IntellijReferenceTargetResult.Confirmed(proof.value)
                    is Refinement.Rejected -> IntellijReferenceTargetResult.Different
                }
        }
    }

    fun confirmJavaTarget(reference: PsiReference, subject: RelationEndpoint): IntellijK2TargetConfirmation =
        when (confirmJavaReferenceTarget(reference, subject)) {
            is IntellijReferenceTargetResult.Confirmed -> IntellijK2TargetConfirmation.EXACT_SUBJECT
            IntellijReferenceTargetResult.Different -> IntellijK2TargetConfirmation.DIFFERENT_SYMBOL
            IntellijReferenceTargetResult.Unresolved -> IntellijK2TargetConfirmation.UNRESOLVED
        }

    /** Detaches one request-local VFS value under the exact selector root. */
    fun detach(file: VirtualFile): IntellijDetachedRelationFile = detachRelationFile(file, workspaceRoot)
}

private fun confirmed() = IntellijK2DefinitionConfirmation.CONFIRMED

private fun different() = IntellijK2DefinitionConfirmation.DIFFERENT_RELATION

private fun rejected(reason: IntellijRelationSubjectFailure) = IntellijRelationSubjectLookup.Rejected(reason)

/** Nullable Java interop results are consumed inside the K2 session and never become evidence. */
internal fun KaSession.nativeSymbol(declaration: PsiNamedElement): org.jetbrains.kotlin.analysis.api.symbols.KaSymbol? =
    when (declaration) {
        is KtNamedDeclaration -> declaration.symbol
        is PsiClass -> declaration.namedClassSymbol
        is PsiMember -> declaration.callableSymbol
        else -> null
    }

internal fun groundedProjection(
    declaration: PsiNamedElement,
    detached: SymbolDiscoveryFileIdentity,
    projection: IntellijCompilerProjection,
): IntellijRelationDeclarationProjection {
    val range = declaration.textRange ?: return IntellijRelationDeclarationProjection.Unsupported
    val evidence =
        when (
            val refined =
                CompilerGroundedSymbolEvidence.fromBoundary(
                    detached,
                    range.startOffset,
                    range.endOffset,
                    declaration.name.orEmpty(),
                    projection.qualifiedIdentity,
                    projection.kind,
                    projection.signature,
                )
        ) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return IntellijRelationDeclarationProjection.Unsupported
        }
    return IntellijRelationDeclarationProjection.Projected(declaration, evidence)
}
