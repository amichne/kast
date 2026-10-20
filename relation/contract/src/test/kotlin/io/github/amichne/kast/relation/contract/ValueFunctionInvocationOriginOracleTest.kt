package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValueFunctionInvocationOriginOracleTest {
    @Test
    fun `a source origin cannot impersonate builtin Function1 invoke`() {
        assertEquals(
            Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE),
            invocation(SourceLessCallableOrigin.SOURCE, SourceLessCallableModuleKind.BUILTINS),
        )
    }

    @Test
    fun `the same canonical signature with a library origin admits the builtin invoke`() {
        val admitted = invocation(SourceLessCallableOrigin.LIBRARY, SourceLessCallableModuleKind.BUILTINS).flowRefined()
        assertEquals(callbackOracleBuiltin().signature, admitted.signature)
        assertEquals(SourceLessCallableOrigin.LIBRARY, admitted.callable.origin)
        assertEquals(SourceLessCallableModuleKind.BUILTINS, admitted.callable.moduleKind)
    }

    @Test
    fun `a Java library origin in a library module cannot impersonate builtin invoke`() {
        assertEquals(
            Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE),
            invocation(SourceLessCallableOrigin.JAVA_LIBRARY, SourceLessCallableModuleKind.LIBRARY),
        )
    }

    @Test
    fun `a Java library origin in a builtin module cannot impersonate builtin invoke`() {
        assertEquals(
            Refinement.Rejected(ValueFunctionInvocationFailure.NOT_BUILTIN_INVOKE),
            invocation(SourceLessCallableOrigin.JAVA_LIBRARY, SourceLessCallableModuleKind.BUILTINS),
        )
    }

    private fun invocation(origin: SourceLessCallableOrigin, moduleKind: SourceLessCallableModuleKind) =
        ValueFunctionInvocation.fromCompiler(
            enclosing = flowEndpoint("inspect", 0, 200),
            range = flowRange(20, 40),
            callable = callable(origin, moduleKind),
        )

    private fun callable(
        origin: SourceLessCallableOrigin,
        moduleKind: SourceLessCallableModuleKind,
    ): SourceLessCallable {
        val prototype = callbackOracleBuiltin()
        return SourceLessCallable.fromCompiler(
                signature = prototype.signature,
                kind = prototype.kind,
                origin = origin,
                moduleKind = moduleKind,
                moduleName = prototype.moduleName,
            )
            .flowRefined()
    }
}
