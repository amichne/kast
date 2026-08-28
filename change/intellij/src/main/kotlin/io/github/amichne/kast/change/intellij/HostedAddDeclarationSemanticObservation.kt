package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticEvidence
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservation
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservationFailure
import io.github.amichne.kast.change.verify.ObservedAddDeclarationDelta
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

internal fun observeHostedAddDeclaration(
    project: Project,
    root: CanonicalWorkspaceRoot,
    workspace: PublishedWorkspace,
    plan: AddDeclarationChangePlan,
): HostedAddDeclarationSemanticObservation {
    if (project.isDisposed) return rejected(
        HostedAddDeclarationSemanticObservationFailure.PROJECT_UNAVAILABLE,
    )
    if (workspace.root != root || plan.priorLease.workspaceRoot != root) {
        return rejected(
            HostedAddDeclarationSemanticObservationFailure.ROOT_OR_GENERATION_MISMATCH,
        )
    }
    return try {
        if (DumbService.getInstance(project).isDumb) {
            rejected(HostedAddDeclarationSemanticObservationFailure.PROJECT_UNAVAILABLE)
        } else {
            ReadAction.nonBlocking<HostedAddDeclarationSemanticObservation> {
                observeRead(project, workspace, plan)
            }.inSmartMode(project).executeSynchronously()
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
}

private fun observeRead(
    project: Project,
    workspace: PublishedWorkspace,
    plan: AddDeclarationChangePlan,
): HostedAddDeclarationSemanticObservation {
    val virtual = LocalFileSystem.getInstance().findFileByNioFile(
        Path.of(plan.target.file.path.value),
    ) ?: return rejected(HostedAddDeclarationSemanticObservationFailure.TARGET_UNAVAILABLE)
    val file = PsiManager.getInstance(project).findFile(virtual) as? KtFile
        ?: return rejected(HostedAddDeclarationSemanticObservationFailure.TARGET_UNAVAILABLE)
    val declarations = PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java)
    val prior = plan.target.selector
    val anchor = declarations.singleOrNull { declaration ->
        declaration.name == prior.name.value &&
            declaration.textRange.startOffset == prior.range.startInclusive
    } ?: return rejected(
        HostedAddDeclarationSemanticObservationFailure.DECLARATION_MISSING_OR_AMBIGUOUS,
    )
    val added = declarations.singleOrNull { declaration ->
        declaration.name == plan.expectedSemanticDelta.declarationName &&
            declaration.addDeclarationKind() == plan.expectedSemanticDelta.declarationKind
    } ?: return rejected(
        HostedAddDeclarationSemanticObservationFailure.DECLARATION_MISSING_OR_AMBIGUOUS,
    )
    if (file.packageFqName.asString() != plan.expectedSemanticDelta.packageName || added.name == null) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
    val qualified = when (val identity = prior.qualifiedIdentity) {
        is ExactDeclarationQualifiedIdentity.Available -> identity.value
        ExactDeclarationQualifiedIdentity.Unavailable -> null
    }
    val evidence = when (val admitted = CompilerGroundedSymbolEvidence.fromBoundary(
        prior.file,
        anchor.textRange.startOffset,
        anchor.textRange.endOffset,
        prior.name.value,
        qualified,
        prior.kind,
        prior.compilerIdentity,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return rejected(
            HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED,
        )
    }
    val delta = when (val admitted = ObservedAddDeclarationDelta.fromCompilerBoundary(
        file.packageFqName.asString(),
        checkNotNull(added.name),
        checkNotNull(added.addDeclarationKind()),
        1,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return rejected(
            HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED,
        )
    }
    return HostedAddDeclarationSemanticObservation.Observed(
        HostedAddDeclarationSemanticEvidence(
            SymbolSelector.issue(workspace.readLease, prior.scope, evidence),
            delta,
        ),
    )
}

private fun KtDeclaration.addDeclarationKind(): AddDeclarationKind? = when (this) {
    is KtClass -> when {
        isInterface() -> AddDeclarationKind.INTERFACE
        isEnum() -> AddDeclarationKind.ENUM_CLASS
        isAnnotation() -> AddDeclarationKind.ANNOTATION_CLASS
        else -> AddDeclarationKind.CLASS
    }
    is KtObjectDeclaration -> AddDeclarationKind.OBJECT
    is KtNamedFunction -> AddDeclarationKind.FUNCTION
    is KtProperty -> AddDeclarationKind.PROPERTY
    is KtTypeAlias -> AddDeclarationKind.TYPE_ALIAS
    else -> null
}

private fun rejected(
    failure: HostedAddDeclarationSemanticObservationFailure,
): HostedAddDeclarationSemanticObservation.Rejected =
    HostedAddDeclarationSemanticObservation.Rejected(failure)
