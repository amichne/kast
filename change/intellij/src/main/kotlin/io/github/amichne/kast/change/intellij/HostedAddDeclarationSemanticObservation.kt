@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

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
import io.github.amichne.kast.change.verify.CompilerReobservedMutationAnchor
import io.github.amichne.kast.change.verify.ObservedAddDeclarationDelta
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
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
    val currentFile = when (val admitted = SymbolDiscoveryFileIdentity.fromBoundary(
        workspace.root,
        Path.of(virtual.path),
        virtual.url,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return rejected(
            HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED,
        )
    }
    val evidence = anchor.compilerEvidence(currentFile)
        ?: return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    val reobserved = when (val admitted = CompilerReobservedMutationAnchor.admit(prior, evidence)) {
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
            SymbolSelector.issue(workspace.readLease, prior.scope, reobserved.evidence),
            delta,
        ),
    )
}

private fun KtNamedDeclaration.compilerEvidence(
    file: SymbolDiscoveryFileIdentity,
): CompilerGroundedSymbolEvidence? {
    val projection = analyze(this) { symbol.compilerProjection() } ?: return null
    return CompilerGroundedSymbolEvidence.fromBoundary(
        file = file,
        rawStartInclusive = textRange.startOffset,
        rawEndExclusive = textRange.endOffset,
        rawName = name.orEmpty(),
        rawQualifiedIdentity = projection.qualifiedIdentity,
        kind = projection.kind,
        signature = projection.signature,
    ).valueOrNull()
}

private data class CompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private fun KaSymbol.compilerProjection(): CompilerProjection? = when (this) {
    is KaConstructorSymbol -> {
        val owner = containingClassId?.asSingleFqName()?.asString() ?: return null
        projected(CompilerSymbolKind.CONSTRUCTOR, "$owner.<init>", functionSignature("$owner.<init>"))
    }
    is KaFunctionSymbol -> {
        val callable = callableId?.asSingleFqName()?.asString() ?: return null
        projected(CompilerSymbolKind.FUNCTION, callable, functionSignature(callable))
    }
    is KaKotlinPropertySymbol -> {
        val callable = callableId?.asSingleFqName()?.asString() ?: return null
        projected(
            CompilerSymbolKind.PROPERTY,
            callable,
            CanonicalCompilerSignature.property(
                rawQualifiedIdentity = callable,
                rawReceiverType = receiverParameter?.returnType?.toString(),
                rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
                rawReturnType = returnType.toString(),
            ),
        )
    }
    is KaTypeAliasSymbol -> {
        val identity = classId?.asSingleFqName()?.asString() ?: return null
        projected(CompilerSymbolKind.TYPE_ALIAS, identity, CanonicalCompilerSignature.typeAlias(identity))
    }
    is KaClassLikeSymbol -> {
        val identity = classId?.asSingleFqName()?.asString() ?: return null
        projected(CompilerSymbolKind.CLASSLIKE, identity, CanonicalCompilerSignature.classLike(identity))
    }
    else -> null
}

private fun KaFunctionSymbol.functionSignature(
    qualifiedIdentity: String,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        rawQualifiedIdentity = qualifiedIdentity,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawValueParameterTypes = valueParameters.map { it.returnType.toString() },
        rawTypeParameterCount = (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun projected(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): CompilerProjection? = when (signature) {
    is Refinement.Refined -> CompilerProjection(kind, qualifiedIdentity, signature.value)
    is Refinement.Rejected -> null
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
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
