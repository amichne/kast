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
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.verify.CompilerReobservedMutationAnchor
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticEvidence
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservation
import io.github.amichne.kast.change.verify.HostedAddDeclarationSemanticObservationFailure
import io.github.amichne.kast.change.verify.ObservedAddDeclarationDelta
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignatureFailure
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
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
    if (project.isDisposed) return rejected(HostedAddDeclarationSemanticObservationFailure.PROJECT_UNAVAILABLE)
    if (workspace.root != root || plan.priorLease.workspaceRoot != root) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.ROOT_OR_GENERATION_MISMATCH)
    }
    return try {
        if (DumbService.getInstance(project).isDumb) {
            rejected(HostedAddDeclarationSemanticObservationFailure.PROJECT_UNAVAILABLE)
        } else {
            ReadAction.nonBlocking<HostedAddDeclarationSemanticObservation> {
                    observeRead(
                        project,
                        workspace.readLease,
                        HostedSemanticObservationInput(
                            prior = CompilerGroundedSymbolEvidence.fromSelector(plan.target.selector),
                            scope = plan.target.selector.scope,
                            constraints = plan.target.selector.constraints,
                            expectedDelta = plan.expectedSemanticDelta,
                        ),
                    )
                }
                .inSmartMode(project)
                .executeSynchronously()
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
}

internal fun observeLiveAddDeclaration(
    project: Project,
    authority: LiveSemanticReadAuthority,
    plan: LiveAddDeclarationChangePlan,
): HostedAddDeclarationSemanticObservation {
    if (project.isDisposed || DumbService.getInstance(project).isDumb) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.PROJECT_UNAVAILABLE)
    }
    if (
        authority.workspaceRoot != plan.basis.observation.reference.workspaceRoot ||
            authority.reference.host != plan.basis.observation.reference.host
    ) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.ROOT_OR_GENERATION_MISMATCH)
    }
    return try {
        ReadAction.nonBlocking<HostedAddDeclarationSemanticObservation> {
                observeRead(
                    project,
                    authority,
                    HostedSemanticObservationInput(
                        prior = plan.target.evidence,
                        scope = plan.target.scope,
                        constraints = plan.target.constraints,
                        expectedDelta = plan.expectedSemanticDelta,
                    ),
                )
            }
            .inSmartMode(project)
            .executeSynchronously()
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
}

private data class HostedSemanticObservationInput(
    val prior: CompilerGroundedSymbolEvidence,
    val scope: SymbolSearchScope,
    val constraints: SymbolDiscoveryConstraints,
    val expectedDelta: ExpectedAddDeclarationDelta,
)

private fun observeRead(
    project: Project,
    authority: SemanticReadAuthority,
    input: HostedSemanticObservationInput,
): HostedAddDeclarationSemanticObservation {
    val prior = input.prior
    val expectedDelta = input.expectedDelta
    val virtual =
        LocalFileSystem.getInstance()
            .findFileByNioFile(
                Path.of(
                    (prior.file as? SymbolDiscoveryFileIdentity.Workspace)?.path?.value
                        ?: return rejected(HostedAddDeclarationSemanticObservationFailure.TARGET_UNAVAILABLE)
                )
            ) ?: return rejected(HostedAddDeclarationSemanticObservationFailure.TARGET_UNAVAILABLE)
    val file =
        PsiManager.getInstance(project).findFile(virtual) as? KtFile
            ?: return rejected(HostedAddDeclarationSemanticObservationFailure.TARGET_UNAVAILABLE)
    val declarations = PsiTreeUtil.collectElementsOfType(file, KtNamedDeclaration::class.java)
    val anchor =
        declarations.singleOrNull { declaration ->
            declaration.name == prior.name.value && declaration.textRange.startOffset == prior.range.startInclusive
        } ?: return rejected(HostedAddDeclarationSemanticObservationFailure.DECLARATION_MISSING_OR_AMBIGUOUS)
    val added =
        declarations.singleOrNull { declaration ->
            declaration.name == expectedDelta.declarationName &&
                declaration.addDeclarationKind() == expectedDelta.declarationKind
        } ?: return rejected(HostedAddDeclarationSemanticObservationFailure.DECLARATION_MISSING_OR_AMBIGUOUS)
    if (file.packageFqName.asString() != expectedDelta.packageName || added.name == null) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
    val currentFile =
        when (
            val admitted =
                SymbolDiscoveryFileIdentity.fromBoundary(
                    authority.workspaceRoot,
                    Path.of(virtual.path),
                    virtual.url,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
        }
    val evidence =
        anchor.compilerEvidence(currentFile)
            ?: return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    if (added.compilerEvidence(currentFile) == null) {
        return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
    }
    val reobserved =
        when (val admitted = CompilerReobservedMutationAnchor.admit(prior, evidence)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
        }
    val delta =
        when (
            val admitted =
                ObservedAddDeclarationDelta.fromCompilerBoundary(
                    packageName = file.packageFqName.asString(),
                    declarationName = checkNotNull(added.name),
                    declarationKind = checkNotNull(added.addDeclarationKind()),
                    matchingDeclarationCount = 1,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(HostedAddDeclarationSemanticObservationFailure.EVIDENCE_REJECTED)
        }
    return HostedAddDeclarationSemanticObservation.Observed(
        HostedAddDeclarationSemanticEvidence(
            SymbolSelector.issue(
                lease = authority,
                scope = input.scope,
                evidence = reobserved.evidence,
                constraints = input.constraints,
            ),
            delta,
        )
    )
}

private fun KtNamedDeclaration.compilerEvidence(file: SymbolDiscoveryFileIdentity): CompilerGroundedSymbolEvidence? {
    val projection = analyze(this) { symbol.compilerProjection() } ?: return null
    return CompilerGroundedSymbolEvidence.fromBoundary(
            file = file,
            rawStartInclusive = textRange.startOffset,
            rawEndExclusive = textRange.endOffset,
            rawName = name.orEmpty(),
            rawQualifiedIdentity = projection.qualifiedIdentity,
            kind = projection.kind,
            signature = projection.signature,
        )
        .valueOrNull()
}

private data class CompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
)

private fun KaSymbol.compilerProjection(): CompilerProjection? =
    when (this) {
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
    qualifiedIdentity: String
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
): CompilerProjection? =
    when (signature) {
        is Refinement.Refined -> CompilerProjection(kind, qualifiedIdentity, signature.value)
        is Refinement.Rejected -> null
    }

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun KtDeclaration.addDeclarationKind(): AddDeclarationKind? =
    when (this) {
        is KtClass ->
            when {
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
    failure: HostedAddDeclarationSemanticObservationFailure
): HostedAddDeclarationSemanticObservation.Rejected = HostedAddDeclarationSemanticObservation.Rejected(failure)
