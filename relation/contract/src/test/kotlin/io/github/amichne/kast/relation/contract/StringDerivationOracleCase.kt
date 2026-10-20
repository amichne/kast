package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals

/** Assumed issued values isolate detached interpolation rules from native syntax and scalar-type proof. */
internal class StringDerivationOracleCase {
    val owner = flowEndpoint("interpolate", 0, 250)
    val execution = execution(owner, 10, 200)
    private val caller = flowEndpoint("caller", 300, 450)
    private val call = ValueInvocation.fromCompiler(caller, flowRange(320, 370), owner).flowRefined()
    val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(owner)).flowRefined()
    val stack = ValueCallStack.Empty.enter(frame).flowRefined()
    val otherCall = ValueInvocation.fromCompiler(caller, flowRange(380, 430), owner).flowRefined()
    val otherFrame = ValueCallFrame.direct(otherCall, ValueExecutionOwner.declaration(owner)).flowRefined()
    val template = flowRange(20, 100)
    val entry = flowRange(30, 60)
    private val checked =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = flowRange(40, 45),
                role = ValueRole.LocalRead,
                executionOwner = execution,
                callStack = stack,
            )
            .flowRefined()
    val predicates =
        ValuePredicates.fromCompiler(
                listOf(ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(checked)))
            )
            .flowRefined()
    val source = checked.withState(predicates = predicates).flowRefined()
    val target = site(template, ValueRole.ExpressionResult)
    val evidence = evidence()

    fun site(range: ExactDeclarationTextRange, role: ValueRole): ValueSite =
        ValueSite.fromCompiler(
                enclosing = owner,
                range = range,
                role = role,
                executionOwner = execution,
                callStack = stack,
                predicates = predicates,
            )
            .flowRefined()

    fun evidence(
        scalarKind: ValueInterpolationScalarKind = ValueInterpolationScalarKind.STRING,
        entryIndex: Int = 0,
    ) =
        ValueTransferEvidence.StringInterpolation.fromCompiler(
                templateRange = template,
                entryRange = entry,
                entryIndex = ValueInterpolationEntryIndex.parse(entryIndex).flowRefined(),
                scalarKind = scalarKind,
            )
            .flowRefined()

    fun transfer(source: ValueSite = this.source, target: ValueSite = this.target) =
        ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = ValueTransferKind.DERIVATION,
                evidence = evidence,
            )
            .flowRefined()

    fun reject(source: ValueSite = this.source, target: ValueSite = this.target) {
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = ValueTransferKind.DERIVATION,
                evidence = evidence,
            ),
        )
    }

    companion object {
        fun execution(owner: RelationEndpoint, start: Int, end: Int): ValueExecutionOwner {
            val range = flowRange(start, end)
            val signature =
                CanonicalCompilerSignature.function(
                        rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                        rawReceiverType = null,
                        rawContextReceiverTypes = emptyList(),
                        rawValueParameterTypes = emptyList(),
                        rawTypeParameterCount = 0,
                    )
                    .flowRefined() as CanonicalCompilerSignature.Function
            val body = RelationCallableBody.Anonymous.fromCompiler(owner.file, range, signature).flowRefined()
            return ValueExecutionOwner.fromCompiler(owner, body, owner.lease.identity).flowRefined()
        }
    }
}
