package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiTypes
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest

internal fun javaProducerInvocation(
    host: PsiMethod,
    enclosing: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    callable: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    request: ValueProducerSeedRequest,
): Refinement<NativeProducerInvocation, ValueProducerSeedRead.Rejected> {
    val call =
        host.exactJavaValueElement(request.anchor) as? PsiMethodCallExpression
            ?: return rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH)
    when (valueNativeOwnership(call, host)) {
        NativeValueOwnership.NESTED -> return rejected(ValueProducerSeedRejection.NESTED_EXECUTION)
        NativeValueOwnership.UNAVAILABLE -> return rejected(ValueProducerSeedRejection.OWNER_UNAVAILABLE)
        NativeValueOwnership.ADMITTED -> Unit
    }
    val method =
        when (val result = NativeJavaStaticCall.resolve(call)) {
            is Refinement.Refined -> result.value.method
            is Refinement.Rejected -> return rejected(result.failure.seed)
        }
    if (method != callable.owner) return rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH)
    if (call.type != PsiTypes.intType()) return rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION)
    if (method is PsiCompiledElement || method.body == null) return rejected(ValueProducerSeedRejection.EXTERNAL_CALL)
    return when (
        val proof =
            revalidateProducerDeclaration(
                native = enclosing,
                declaration = method,
                selector = request.expectedCallable,
            )
    ) {
        is Refinement.Refined ->
            Refinement.Refined(
                NativeProducerInvocation(
                    callable = proof.value.endpoint,
                    expression = call,
                    owner = ValueExecutionOwner.declaration(RelationEndpoint.subject(request.enclosing)),
                    captureWork = 0L,
                )
            )
        is Refinement.Rejected -> rejected(proof.failure)
    }
}

private fun rejected(cause: ValueProducerSeedRejection) = Refinement.Rejected(ValueProducerSeedRead.Rejected(cause))
