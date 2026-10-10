@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationEndpointResolutionFailure
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/** Request-local native projection and shared allowance; no live PSI or K2 object escapes the read. */
internal class IntellijCallbackFlowContext(
    val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val admitWork: () -> CallbackWorkAdmission,
    val observation: IntellijReadObservation,
    private val callbackExpiry: () -> CallbackExpiryAdmission,
) {
    fun providerSite(
        file: () -> com.intellij.openapi.vfs.VirtualFile?
    ): Refinement<RelationProviderScopeAdmission, CallbackInvocationFlowCause> =
        admitCallbackProviderSite(com.intellij.openapi.progress.ProgressManager::checkCanceled, callbackExpiry) {
            scope.admitProviderSite(file())
        }

    fun admitNativeScope(): Refinement<Unit, CallbackInvocationFlowCause> {
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        return when (callbackExpiry()) {
            CallbackExpiryAdmission.CURRENT -> Refinement.Refined(Unit)
            CallbackExpiryAdmission.EXPIRED -> Refinement.Rejected(CallbackInvocationFlowCause.TIME_LIMIT_REACHED)
        }
    }

    fun permit(): Refinement<Unit, CallbackInvocationFlowCause> =
        when (admitWork()) {
            CallbackWorkAdmission.READY -> Refinement.Refined(Unit)
            CallbackWorkAdmission.WORK_LIMIT_REACHED ->
                Refinement.Rejected(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
            CallbackWorkAdmission.TIME_LIMIT_REACHED ->
                Refinement.Rejected(CallbackInvocationFlowCause.TIME_LIMIT_REACHED)
        }

    fun unavailable(
        body: RelationCallableBody.Anonymous,
        cause: CallbackInvocationFlowCause,
    ): CallbackInvocationFlowRead =
        observed(
            body = body,
            binding = CallbackBindingEvidence.Unavailable(cause),
            invocations = emptyList(),
            obligations = setOf(cause),
        )

    fun observed(
        body: RelationCallableBody.Anonymous,
        binding: CallbackBindingEvidence,
        invocations: List<CallbackParameterInvocation>,
        obligations: Set<CallbackInvocationFlowCause>,
        scan: CallbackInvocationScan = CallbackInvocationScan.INCOMPLETE,
        forwarding: CallbackForwardingEvidence = CallbackForwardingEvidence.InvocationRoutes,
    ): CallbackInvocationFlowRead =
        when (
            val result =
                CallbackInvocationFlow.fromCompiler(
                    basis = scope.request.subject.lease.identity,
                    body = body,
                    binding = binding,
                    invocations = invocations,
                    obligations = obligations,
                    scan = scan,
                    forwarding = forwarding,
                )
        ) {
            is Refinement.Refined -> CallbackInvocationFlowRead.Observed(result.value)
            is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(result.failure)
        }

    fun endpoint(evidence: CompilerGroundedSymbolEvidence): RelationEndpoint.Resolved? =
        when (
            val result =
                RelationEndpoint.resolve(
                    lease = scope.request.subject.lease,
                    scope = scope.request.searchScope,
                    evidence = evidence,
                    constraints = scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> null
        }

    fun receiverDeclaration(element: com.intellij.psi.PsiNamedElement): CompilerGroundedSymbolEvidence? =
        when (val result = projection.project(element)) {
            is IntellijRelationDeclarationProjection.Projected -> result.evidence
            IntellijRelationDeclarationProjection.Unsupported -> null
        }

    fun target(function: KtNamedFunction): Refinement<RelationEndpoint.Resolved, CallbackInvocationFlowCause> {
        when (scope.request.searchConstraints.packageName.admitPackage { function.relationPackageEvidence() }) {
            IntellijRelationPackageAdmission.ADMITTED -> Unit
            IntellijRelationPackageAdmission.OUTSIDE_SCOPE ->
                return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
            IntellijRelationPackageAdmission.UNSUPPORTED ->
                return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
        return bindingTarget(function)
    }

    /** Evidence-only mapping target; callers must retain scope qualification and cannot enumerate its body. */
    fun bindingTarget(function: KtNamedFunction): Refinement<RelationEndpoint.Resolved, CallbackInvocationFlowCause> {
        val evidence =
            when (val result = projection.project(function)) {
                is IntellijRelationDeclarationProjection.Projected -> result.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
            }
        return when (
            val result =
                RelationEndpoint.resolve(
                    lease = scope.request.subject.lease,
                    scope = scope.request.searchScope,
                    evidence = evidence,
                    constraints = scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> result
            is Refinement.Rejected ->
                when (result.failure) {
                    RelationEndpointResolutionFailure.FILE_OUTSIDE_EXACT_SCOPE ->
                        Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
                }
        }
    }

    fun owner(element: PsiElement): RelationCallableBody? =
        when (val found = element.nearestDeclaration()) {
            is ContainingDeclaration.Found ->
                when (val result = projection.project(found.declaration)) {
                    is IntellijRelationDeclarationProjection.Projected ->
                        when (val body = RelationCallableBody.Named.fromCompiler(result.evidence)) {
                            is Refinement.Refined -> body.value
                            is Refinement.Rejected -> null
                        }
                    IntellijRelationDeclarationProjection.Unsupported -> null
                }
            is ContainingDeclaration.Deferred -> (found.boundary as? KtFunction)?.let(::anonymous)
            ContainingDeclaration.Unsupported -> null
        }

    fun anonymous(literal: KtFunction): RelationCallableBody.Anonymous? {
        val nativeFile = literal.containingFile?.virtualFile ?: return null
        if (!scope.nativeScope.contains(nativeFile)) return null
        val file =
            when (val detached = detachRelationFile(nativeFile, scope.request.subject.lease.workspaceRoot)) {
                is IntellijDetachedRelationFile.Found -> detached.identity
                IntellijDetachedRelationFile.Unsupported -> return null
            }
        val range = range(literal) ?: return null
        val signature =
            observation.observedAnalyze(literal) {
                val symbol =
                    when (literal) {
                        is KtFunctionLiteral -> literal.symbol
                        is KtNamedFunction -> literal.symbol
                        else -> null
                    }
                        as? KaFunctionSymbol ?: return@observedAnalyze null
                when (
                    val parsed =
                        CanonicalCompilerSignature.function(
                            rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(file, range),
                            rawReceiverType = symbol.receiverParameter?.returnType?.toString(),
                            rawContextReceiverTypes = symbol.contextReceivers.map { it.type.toString() },
                            rawValueParameterTypes = symbol.valueParameters.map { it.returnType.toString() },
                            rawTypeParameterCount = 0,
                        )
                ) {
                    is Refinement.Refined -> parsed.value as CanonicalCompilerSignature.Function
                    is Refinement.Rejected -> null
                }
            } ?: return null
        return when (val admitted = RelationCallableBody.Anonymous.fromCompiler(file, range, signature)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> null
        }
    }

    fun occurrence(element: PsiElement): RelationOccurrence? {
        val nativeFile = element.containingFile?.virtualFile ?: return null
        val range = range(element) ?: return null
        val file =
            when (val detached = detachRelationFile(nativeFile, scope.request.subject.lease.workspaceRoot)) {
                is IntellijDetachedRelationFile.Found -> detached.identity
                IntellijDetachedRelationFile.Unsupported -> return null
            }
        return when (val admitted = RelationOccurrence.fromBoundary(file, range.startInclusive, range.endExclusive)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> null
        }
    }

    fun range(element: PsiElement): ExactDeclarationTextRange? {
        val nativeRange = element.textRange ?: return null
        return when (val result = ExactDeclarationTextRange.parse(nativeRange.startOffset, nativeRange.endOffset)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> null
        }
    }

    fun site(element: PsiElement, target: RelationEndpoint, role: ValueRole): ValueSite? {
        val range = range(element) ?: return null
        return when (val result = ValueSite.fromCompiler(target, range, role)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> null
        }
    }

    fun parameterInvocation(expression: KtNameReferenceExpression): KtCallExpression? {
        val call =
            when (val parent = expression.parent) {
                is KtCallExpression -> parent.takeIf { it.calleeExpression === expression }
                is KtDotQualifiedExpression,
                is KtSafeQualifiedExpression ->
                    if (parent.receiverExpression === expression)
                        (parent.selectorExpression as? KtCallExpression)?.takeIf {
                            it.calleeExpression?.text == "invoke"
                        }
                    else null
                else -> null
            } ?: return null
        return if (confirmsFunctionInvoke(call)) call else null
    }

    fun confirmsFunctionInvoke(call: KtCallExpression): Boolean =
        observation.observedAnalyze(call) {
            val symbol = call.resolveCall()?.signature?.symbol as? KaNamedFunctionSymbol ?: return@observedAnalyze false
            confirmsBuiltinFunctionInvoke(symbol)
        }
}
