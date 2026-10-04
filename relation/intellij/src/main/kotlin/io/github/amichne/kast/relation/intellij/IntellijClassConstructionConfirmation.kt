@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol

internal fun confirmClassConstruction(
    admitted: IntellijRelationReferenceAdmission.Admitted.ClassConstruction,
    workspaceRoot: CanonicalWorkspaceRoot,
    observation: IntellijReadObservation,
): IntellijK2TargetConfirmation =
    analyze(admitted.reference.element) {
        if (nativeSymbol(admitted.selectedClass) !is KaClassSymbol)
            return@analyze unresolvedConstruction(IntellijReadTermination.K2_UNRESOLVED_SYMBOL, observation)
        val resolved =
            admitted.reference.resolveToSymbol()
                ?: return@analyze unresolvedConstruction(IntellijReadTermination.K2_UNRESOLVED_SYMBOL, observation)
        val constructor =
            resolved as? KaConstructorSymbol ?: return@analyze IntellijK2TargetConfirmation.DIFFERENT_SYMBOL
        val owner =
            constructor.containingSymbol as? KaClassSymbol
                ?: return@analyze unresolvedConstruction(
                    IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED,
                    observation,
                )
        val evidence =
            when (val result = constructorEvidence(owner, workspaceRoot)) {
                is ConstructorEvidence.Proven -> result.evidence
                is ConstructorEvidence.Unresolved -> return@analyze unresolvedConstruction(result.reason, observation)
            }
        when (
            io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget.fromCompiler(
                admitted.endpoint,
                evidence,
            )
        ) {
            is Refinement.Refined -> IntellijK2TargetConfirmation.EXACT_SUBJECT
            is Refinement.Rejected -> IntellijK2TargetConfirmation.DIFFERENT_SYMBOL
        }
    }

private fun unresolvedConstruction(
    reason: IntellijReadTermination,
    observation: IntellijReadObservation,
): IntellijK2TargetConfirmation {
    observation.terminated(reason)
    return IntellijK2TargetConfirmation.UNRESOLVED
}

private sealed interface ConstructorEvidence {
    data class Proven(val evidence: CompilerGroundedSymbolEvidence) : ConstructorEvidence

    data class Unresolved(val reason: IntellijReadTermination) : ConstructorEvidence
}

private fun KaSession.constructorEvidence(
    owner: KaClassSymbol,
    workspaceRoot: CanonicalWorkspaceRoot,
): ConstructorEvidence {
    val anchor = owner.psi ?: return ConstructorEvidence.Unresolved(IntellijReadTermination.K2_SYMBOL_WITHOUT_PSI)
    val declaration =
        anchor as? PsiNamedElement ?: return ConstructorEvidence.Unresolved(IntellijReadTermination.K2_NON_KOTLIN_PSI)
    val file =
        declaration.containingFile?.virtualFile
            ?: return ConstructorEvidence.Unresolved(IntellijReadTermination.DISCOVERY_SOURCE_UNAVAILABLE)
    val detached =
        when (val result = detachRelationFile(file, workspaceRoot)) {
            is IntellijDetachedRelationFile.Found -> result.identity
            IntellijDetachedRelationFile.Unsupported ->
                return ConstructorEvidence.Unresolved(IntellijReadTermination.DISCOVERY_SOURCE_UNAVAILABLE)
        }
    val compiler =
        when (val projected = owner.compilerProjection()) {
            is IntellijCompilerProjectionResult.Projected -> projected.projection
            IntellijCompilerProjectionResult.Unsupported ->
                return ConstructorEvidence.Unresolved(IntellijReadTermination.EXACT_REFINEMENT_UNAVAILABLE)
        }
    val evidence =
        when (val result = groundedProjection(declaration, detached, compiler)) {
            is IntellijRelationDeclarationProjection.Projected -> result.evidence
            IntellijRelationDeclarationProjection.Unsupported ->
                return ConstructorEvidence.Unresolved(IntellijReadTermination.EXACT_REFINEMENT_UNAVAILABLE)
        }
    return ConstructorEvidence.Proven(evidence)
}
