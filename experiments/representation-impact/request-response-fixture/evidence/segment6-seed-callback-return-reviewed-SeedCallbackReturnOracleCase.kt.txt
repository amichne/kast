package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

/** Assumed compiler callback facts isolate the seed return law from native supplier and body discovery. */
internal class SeedCallbackReturnOracleCase(generation: Long = 1) {
    val callback = ValueCallbackOracleCase(generation = generation)
    val frame = callback.frame()
    private val bare =
        ValueSite.fromCompiler(
                enclosing = callback.host,
                range = flowRange(50, 55),
                role = ValueRole.Return,
                executionOwner = callback.callback,
            )
            .flowRefined()
    val path = flowPath(ValuePathSegment.Field(flowField("Response.value", 700, 720, generation = generation)))
    val predicates =
        ValuePredicates.fromCompiler(
                listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(bare)))
            )
            .flowRefined()
    val source = bare.withState(valuePath = path, predicates = predicates).flowRefined()
    val expectedStack = ValueCallStack.Empty.enter(callback.supplyingFrame).flowRefined()
    val target = result()

    fun returned(owner: ValueExecutionOwner): ValueSite =
        ValueSite.fromCompiler(
                enclosing = owner.enclosing,
                range = source.range,
                role = ValueRole.Return,
                executionOwner = owner,
                valuePath = path,
                predicates = predicates,
            )
            .flowRefined()

    fun result(invocation: ValueCallOccurrence = callback.invocation): ValueSite =
        ValueSite.fromCompiler(
                enclosing = invocation.enclosing,
                range = invocation.range,
                role = ValueRole.ExpressionResult,
                executionOwner = invocation.executionOwner,
                valuePath = path,
                callStack = expectedStack,
                predicates = predicates,
            )
            .flowRefined()

    fun transfer(
        source: ValueSite = this.source,
        target: ValueSite = this.target,
        frame: ValueCallFrame = this.frame,
    ) =
        ValueTransfer.fromCompiler(
            source = source,
            target = target,
            kind = ValueTransferKind.SOURCE_CALL_RETURN,
            evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.SeedCallbackReturn(frame)),
        )

    fun rejected(
        source: ValueSite = this.source,
        target: ValueSite = this.target,
        frame: ValueCallFrame = this.frame,
        failure: ValueTransferFailure = ValueTransferFailure.EVIDENCE_MISMATCH,
    ) {
        assertEquals(Refinement.Rejected(failure), transfer(source, target, frame))
    }
}

internal fun seedReturnChangedScope(original: RelationEndpoint.Resolved): RelationEndpoint.Resolved =
    RelationEndpoint.resolve(
            lease = original.lease,
            scope = (original.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
            evidence = original.evidence,
            constraints = original.constraints,
        )
        .flowRefined()
        .also { assertNotEquals(original.scope, it.scope) }
