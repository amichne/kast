package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.SourceLessCallable
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleKind
import io.github.amichne.kast.relation.contract.SourceLessCallableModuleName
import io.github.amichne.kast.relation.contract.SourceLessCallableOrigin
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFunctionInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Detached values exercise native failure projection without replacing compiler resolution. */
internal class NativeCallbackFailureCase {
    fun argument(): ValueSite =
        site(ValueRole.FunctionArgument(invocation(), ValueArgumentPosition.parse(0).failureValue()))

    fun expression(): ValueSite = site(ValueRole.ExpressionResult)

    private fun invocation(): ValueFunctionInvocation {
        val callable =
            SourceLessCallable.fromCompiler(
                    signature =
                        CanonicalCompilerSignature.function(
                                "kotlin.Function1.invoke",
                                null,
                                emptyList(),
                                listOf("String"),
                                0,
                            )
                            .failureValue(),
                    kind = CompilerSymbolKind.FUNCTION,
                    origin = SourceLessCallableOrigin.LIBRARY,
                    moduleKind = SourceLessCallableModuleKind.BUILTINS,
                    moduleName = SourceLessCallableModuleName.parse("Built-ins").failureValue(),
                )
                .failureValue()
        return ValueFunctionInvocation.fromCompiler(enclosing(), range(42, 49), callable).failureValue()
    }

    private fun enclosing() = RelationReadTest().request(RelationMeaning.Callees).subject

    private fun site(role: ValueRole) = ValueSite.fromCompiler(enclosing(), range(45, 46), role).failureValue()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).failureValue()
}

internal fun <T> Refinement<T, *>.failureValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Detached fixture rejected: $failure")
    }
