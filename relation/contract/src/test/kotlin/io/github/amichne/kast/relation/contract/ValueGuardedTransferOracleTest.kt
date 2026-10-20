package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueGuardedTransferOracleTest {
    @Test
    fun `a guarded local transfer keeps the previous fact and the exact added fact`() {
        val case = guardedCase()
        val transfer =
            ValueTransfer.fromCompiler(
                    source = case.source,
                    target = case.target,
                    kind = ValueTransferKind.LOCAL_BINDING,
                    evidence = case.evidence,
                )
                .flowRefined()
        assertSame(case.target.predicates, transfer.target.predicates)
        assertEquals(case.source.predicates.entries + case.added.entries, transfer.target.predicates.entries)
        assertSame(case.source.valuePath, transfer.target.valuePath)
        assertSame(case.source.callStack, transfer.target.callStack)
        assertEquals(case.source.executionOwner, transfer.target.executionOwner)
    }

    @Test
    fun `a guarded transfer cannot drop a previous fact or the added fact`() {
        val case = guardedCase()
        reject(case, case.target.withState(predicates = case.added).flowRefined())
        reject(case, case.target.withState(predicates = case.source.predicates).flowRefined())
    }

    @Test
    fun `a guarded transfer cannot invent a third fact`() {
        val case = guardedCase()
        val unrelated = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(predicateOracleSite(90)))
        val expanded = ValuePredicates.fromCompiler(case.target.predicates.entries + unrelated).flowRefined()
        reject(case, case.target.withState(predicates = expanded).flowRefined())
    }

    @Test
    fun `ordinary evidence cannot change a conjunction and a guard cannot forge a field read`() {
        val case = guardedCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(case.source, case.target, ValueTransferKind.LOCAL_BINDING),
        )
        val result = predicateOracleSite(60).withState(predicates = case.target.predicates).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.source,
                target = result,
                kind = ValueTransferKind.FIELD_READ,
                evidence = case.evidence,
            ),
        )
    }

    @Test
    fun `a guarded field read keeps the exact field projection and both scoped facts`() {
        val case = guardedCase()
        val field = flowField("Response.value", 500, 520)
        val source = case.source.withState(valuePath = flowPath(ValuePathSegment.Field(field))).flowRefined()
        val target = predicateOracleSite(60).withState(predicates = case.target.predicates).flowRefined()
        val delegate = ValueSourceTransfer.fromCompiler(ValueSourceOperation.ReadField(field))
        val evidence = ValueTransferEvidence.Guarded.fromCompiler(delegate, case.added).flowRefined()
        val transfer =
            ValueTransfer.fromCompiler(
                    source = source,
                    target = target,
                    kind = ValueTransferKind.FIELD_READ,
                    evidence = evidence,
                )
                .flowRefined()
        assertSame(delegate, (transfer.evidence as ValueTransferEvidence.Guarded).delegate)
        assertEquals(ValueAccessPath.Whole, transfer.target.valuePath)
        assertEquals(case.target.predicates, transfer.target.predicates)
        val sibling = flowField("Response.error", 530, 550)
        val wrong =
            ValueTransferEvidence.Guarded.fromCompiler(
                    ValueSourceTransfer.fromCompiler(ValueSourceOperation.ReadField(sibling)),
                    case.added,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = source,
                target = target,
                kind = ValueTransferKind.FIELD_READ,
                evidence = wrong,
            ),
        )
    }

    @Test
    fun `guard evidence cannot recursively wrap another guard`() {
        val case = guardedCase()
        assertEquals(
            Refinement.Rejected(ValuePredicateFailure.NESTED_GUARD),
            ValueTransferEvidence.Guarded.fromCompiler(case.evidence, case.added),
        )
    }

    private fun guardedCase(): GuardedCase {
        val base = predicateOracleSite(20)
        val subject = ValuePredicateSubject.fromCompiler(base)
        val previous = ValuePredicate.NonNull.fromCompiler(subject)
        val addedFact = ValuePredicate.Subtype.fromCompiler(subject, predicateOracleClass()).flowRefined()
        val source =
            base.withState(predicates = ValuePredicates.fromCompiler(listOf(previous)).flowRefined()).flowRefined()
        val added = ValuePredicates.fromCompiler(listOf(addedFact)).flowRefined()
        val expected = ValuePredicates.fromCompiler(listOf(previous, addedFact)).flowRefined()
        val target = predicateOracleSite(60, ValueRole.LocalBinding).withState(predicates = expected).flowRefined()
        val evidence = ValueTransferEvidence.Guarded.fromCompiler(ValueTransferEvidence.Direct, added).flowRefined()
        return GuardedCase(source, target, added, evidence)
    }

    private fun reject(case: GuardedCase, target: ValueSite) {
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.source,
                target = target,
                kind = ValueTransferKind.LOCAL_BINDING,
                evidence = case.evidence,
            ),
        )
    }
}

private data class GuardedCase(
    val source: ValueSite,
    val target: ValueSite,
    val added: ValuePredicates,
    val evidence: ValueTransferEvidence.Guarded,
)
