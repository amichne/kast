@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Path

internal enum class IntellijRelationSubjectFailure {
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
}

internal sealed interface IntellijRelationSubjectLookup {
    data class Found(
        val declaration: KtNamedDeclaration,
        val evidence: CompilerGroundedSymbolEvidence,
    ) : IntellijRelationSubjectLookup

    data class Rejected(
        val reason: IntellijRelationSubjectFailure,
    ) : IntellijRelationSubjectLookup
}

internal sealed interface IntellijRelationDeclarationProjection {
    data class Projected(
        val declaration: KtNamedDeclaration,
        val evidence: CompilerGroundedSymbolEvidence,
    ) : IntellijRelationDeclarationProjection

    data object Unsupported : IntellijRelationDeclarationProjection
}

internal enum class IntellijK2TargetConfirmation {
    EXACT_SUBJECT,
    DIFFERENT_SYMBOL,
    UNRESOLVED,
}

internal enum class IntellijK2DefinitionConfirmation {
    CONFIRMED,
    DIFFERENT_RELATION,
    UNSUPPORTED,
}

internal sealed interface IntellijK2ResolvedDeclaration {
    data class Found(val declaration: KtNamedDeclaration) : IntellijK2ResolvedDeclaration
    data object Unresolved : IntellijK2ResolvedDeclaration
}

internal sealed interface IntellijDetachedRelationFile {
    data class Found(val identity: SymbolDiscoveryFileIdentity) : IntellijDetachedRelationFile
    data object Unsupported : IntellijDetachedRelationFile
}

/** Request-local exact lookup and K2 projection for relation subjects and endpoints. */
internal class IntellijK2RelationProjection(
    private val project: com.intellij.openapi.project.Project,
    private val workspaceRoot: CanonicalWorkspaceRoot,
) {
    /**
     * Proof transition: `(CompiledRelationScope, RelationEndpoint) ->
     * IntellijRelationSubjectLookup`.
     *
     * A found result establishes exact file/range/name PSI lookup plus identical K2 compiler
     * evidence for the endpoint. [IntellijRelationSubjectFailure] is the closed expected failure.
     * Live VFS, PSI, and K2 values remain inside this request-local adapter.
     */
    fun subject(
        scope: CompiledRelationScope,
        subject: RelationEndpoint,
    ): IntellijRelationSubjectLookup {
        val file = when (val identity = subject.file) {
                       is SymbolDiscoveryFileIdentity.Workspace ->
                           LocalFileSystem.getInstance().findFileByNioFile(Path.of(identity.path.value))
                       is SymbolDiscoveryFileIdentity.External ->
                           VirtualFileManager.getInstance().findFileByUrl(identity.url.value)
                   } ?: return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
        if (!scope.nativeScope.contains(file)) {
            return rejected(IntellijRelationSubjectFailure.OUTSIDE_SCOPE)
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
                      ?: return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
        val candidates = generateSequence(psiFile.findElementAt(subject.range.startInclusive)) {
            it.parent
        }
            .filterIsInstance<KtNamedDeclaration>()
            .filter { declaration ->
                declaration.textRange.startOffset == subject.range.startInclusive &&
                declaration.textRange.endOffset == subject.range.endExclusive &&
                declaration.name == subject.name.value
            }
            .toList()
        val declaration = when (candidates.size) {
            0 -> return rejected(IntellijRelationSubjectFailure.STALE_SELECTOR)
            1 -> candidates.single()
            else -> return rejected(IntellijRelationSubjectFailure.AMBIGUOUS_SUBJECT)
        }
        val evidence = when (val projection = project(declaration)) {
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
     * A projected result establishes exact detached file/range/name/kind and overload-aware K2
     * identity. Unsupported files, declarations, or local/unavailable compiler identities remain
     * closed as [IntellijRelationDeclarationProjection.Unsupported]. Live values remain local.
     */
    fun project(declaration: KtNamedDeclaration): IntellijRelationDeclarationProjection {
        val file = declaration.containingFile?.virtualFile
                   ?: return IntellijRelationDeclarationProjection.Unsupported
        val detached = when (val result = file.detachNative()) {
            is IntellijDetachedRelationFile.Found -> result.identity
            IntellijDetachedRelationFile.Unsupported ->
                return IntellijRelationDeclarationProjection.Unsupported
        }
        val projection = when (val result = analyze(declaration) {
            declaration.symbol.compilerProjection()
        }) {
            is IntellijCompilerProjectionResult.Projected -> result.projection
            IntellijCompilerProjectionResult.Unsupported ->
                return IntellijRelationDeclarationProjection.Unsupported
        }
        val evidence = when (
            val refined = CompilerGroundedSymbolEvidence.fromBoundary(
                detached,
                declaration.textRange.startOffset,
                declaration.textRange.endOffset,
                declaration.name.orEmpty(),
                projection.qualifiedIdentity,
                projection.kind,
                projection.identity,
            )
        ) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return IntellijRelationDeclarationProjection.Unsupported
        }
        return IntellijRelationDeclarationProjection.Projected(declaration, evidence)
    }

    /** Confirms through K2 that one Kotlin reference resolves to the exact request subject. */
    fun confirmTarget(
        reference: KtReference,
        subject: RelationEndpoint,
    ): IntellijK2TargetConfirmation {
        val identity = when (val result = analyze(reference.element) {
            val symbol = reference.resolveToSymbol()
                         ?: return@analyze IntellijCompilerProjectionResult.Unsupported
            symbol.compilerProjection()
        }) {
            is IntellijCompilerProjectionResult.Projected -> result.projection.identity
            IntellijCompilerProjectionResult.Unsupported ->
                return IntellijK2TargetConfirmation.UNRESOLVED
        }
        return if (identity == subject.compilerIdentity) {
            IntellijK2TargetConfirmation.EXACT_SUBJECT
        } else {
            IntellijK2TargetConfirmation.DIFFERENT_SYMBOL
        }
    }

    /**
     * Confirms the closed implementation, inheritance, or override meaning through K2 relation
     * APIs; index enumeration alone never admits a definition edge.
     */
    fun confirmDefinition(
        subject: KtNamedDeclaration,
        candidate: KtNamedDeclaration,
        meaning: RelationMeaning,
    ): IntellijK2DefinitionConfirmation = analyze(candidate) {
        val subjectSymbol = subject.symbol
        val candidateSymbol = candidate.symbol
        when (meaning) {
            RelationMeaning.Inheritors -> {
                val parent = subjectSymbol as? KaClassSymbol
                             ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                val child = candidateSymbol as? KaClassSymbol
                            ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                if (child.isDirectSubClassOf(parent)) confirmed() else different()
            }
            RelationMeaning.Overrides -> {
                val parent = subjectSymbol as? KaCallableSymbol
                             ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                val child = candidateSymbol as? KaCallableSymbol
                            ?: return@analyze IntellijK2DefinitionConfirmation.UNSUPPORTED
                if (
                    child.directlyOverriddenSymbols.any {
                        it.compareIdentity(parent) == IntellijSymbolIdentityComparison.SAME
                    }
                ) {
                    confirmed()
                } else {
                    different()
                }
            }
            RelationMeaning.Implementations -> when {
                subjectSymbol is KaClassSymbol && candidateSymbol is KaClassSymbol ->
                    if (
                        candidateSymbol.modality != KaSymbolModality.ABSTRACT &&
                        candidateSymbol.isSubClassOf(subjectSymbol)
                    ) confirmed() else different()
                subjectSymbol is KaCallableSymbol && candidateSymbol is KaCallableSymbol ->
                    if (
                        candidateSymbol.modality != KaSymbolModality.ABSTRACT &&
                        candidateSymbol.allOverriddenSymbols.any {
                            it.compareIdentity(subjectSymbol) ==
                                IntellijSymbolIdentityComparison.SAME
                        }
                    ) confirmed() else different()
                else -> IntellijK2DefinitionConfirmation.UNSUPPORTED
            }
            RelationMeaning.References,
            RelationMeaning.Callers,
            RelationMeaning.Callees,
            RelationMeaning.TypeUses,
                -> IntellijK2DefinitionConfirmation.UNSUPPORTED
        }
    }

    /** Resolves one Kotlin call/reference target to a source declaration through K2. */
    fun resolve(reference: KtReference): IntellijK2ResolvedDeclaration = analyze(reference.element) {
        val declaration = reference.resolveToSymbol()?.psi as? KtNamedDeclaration
        if (declaration == null) {
            IntellijK2ResolvedDeclaration.Unresolved
        } else {
            IntellijK2ResolvedDeclaration.Found(declaration)
        }
    }

    /** Detaches one request-local VFS value under the exact selector root. */
    fun detach(file: VirtualFile): IntellijDetachedRelationFile = file.detachNative()

    private fun VirtualFile.detachNative(): IntellijDetachedRelationFile {
        val native = when (val classified = relationNativePath(this)) {
            is IntellijRelationNativePath.Absolute -> classified.value
            IntellijRelationNativePath.Relative,
            IntellijRelationNativePath.Unavailable,
                -> null
        }
        return when (
            val detached = SymbolDiscoveryFileIdentity.fromBoundary(
                workspaceRoot,
                native,
                url,
            )
        ) {
            is Refinement.Refined -> IntellijDetachedRelationFile.Found(detached.value)
            is Refinement.Rejected -> IntellijDetachedRelationFile.Unsupported
        }
    }
}

private fun confirmed() = IntellijK2DefinitionConfirmation.CONFIRMED
private fun different() = IntellijK2DefinitionConfirmation.DIFFERENT_RELATION

private fun rejected(reason: IntellijRelationSubjectFailure) =
    IntellijRelationSubjectLookup.Rejected(reason)
