package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueStringDerivationOracleTest {
    @Test
    fun `entry index accepts zero and maximum Int but rejects negative input`() {
        assertEquals(0, ValueInterpolationEntryIndex.parse(0).flowRefined().value)
        assertEquals(Int.MAX_VALUE, ValueInterpolationEntryIndex.parse(Int.MAX_VALUE).flowRefined().value)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueInterpolationEntryIndex.parse(-1),
        )
    }

    @Test
    fun `each admitted scalar kind keeps its exact template entry and index`() {
        val case = StringDerivationOracleCase()
        val expected =
            listOf(
                ValueInterpolationScalarKind.STRING,
                ValueInterpolationScalarKind.BOOLEAN,
                ValueInterpolationScalarKind.CHAR,
                ValueInterpolationScalarKind.BYTE,
                ValueInterpolationScalarKind.SHORT,
                ValueInterpolationScalarKind.INT,
                ValueInterpolationScalarKind.LONG,
                ValueInterpolationScalarKind.FLOAT,
                ValueInterpolationScalarKind.DOUBLE,
            )
        assertEquals(expected, ValueInterpolationScalarKind.entries)
        for (kind in expected) {
            val evidence = case.evidence(kind, 2)
            assertEquals(case.template, evidence.templateRange)
            assertEquals(case.entry, evidence.entryRange)
            assertEquals(2, evidence.entryIndex.value)
            assertEquals(kind, evidence.scalarKind)
            val transfer =
                ValueTransfer.fromCompiler(
                        source = case.source,
                        target = case.target,
                        kind = ValueTransferKind.DERIVATION,
                        evidence = evidence,
                    )
                    .flowRefined()
            assertSame(evidence, transfer.evidence)
        }
    }

    @Test
    fun `whole LocalRead scalar derives the exact whole template with all state`() {
        val case = StringDerivationOracleCase()
        val transfer = case.transfer()
        assertSame(case.source, transfer.source)
        assertSame(case.target, transfer.target)
        assertEquals(ValueTransferKind.DERIVATION, transfer.kind)
        assertEquals(ValueAccessPath.Whole, transfer.source.valuePath)
        assertEquals(ValueAccessPath.Whole, transfer.target.valuePath)
        assertSame(case.stack, transfer.target.callStack)
        assertSame(case.predicates, transfer.target.predicates)
        assertSame(case.execution, transfer.target.executionOwner)
    }

    @Test
    fun `whole expression scalar also derives the template`() {
        val case = StringDerivationOracleCase()
        val source = case.site(case.source.range, ValueRole.ExpressionResult)
        assertSame(source, case.transfer(source = source).source)
    }

    @Test
    fun `entry must lie strictly inside the template`() {
        val case = StringDerivationOracleCase()
        val invalid = listOf(case.template, flowRange(20, 60), flowRange(30, 100), flowRange(90, 110))
        for (range in invalid) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransferEvidence.StringInterpolation.fromCompiler(
                    templateRange = case.template,
                    entryRange = range,
                    entryIndex = ValueInterpolationEntryIndex.parse(0).flowRefined(),
                    scalarKind = ValueInterpolationScalarKind.INT,
                ),
            )
        }
    }

    @Test
    fun `direct evidence cannot assert a derivation`() {
        val case = StringDerivationOracleCase()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(case.source, case.target, ValueTransferKind.DERIVATION),
        )
    }

    @Test
    fun `interpolation evidence cannot assert an ordinary transfer kind`() {
        val case = StringDerivationOracleCase()
        for (kind in ValueTransferKind.entries.filter { it != ValueTransferKind.DERIVATION }) {
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(
                    source = case.source,
                    target = case.target,
                    kind = kind,
                    evidence = case.evidence,
                ),
            )
        }
    }

    @Test
    fun `another template or an input outside the exact entry cannot borrow interpolation evidence`() {
        val case = StringDerivationOracleCase()
        case.reject(target = case.site(flowRange(20, 110), ValueRole.ExpressionResult))
        case.reject(source = case.site(flowRange(70, 75), ValueRole.LocalRead))
    }

    @Test
    fun `another entry in the same template cannot borrow the scalar occurrence`() {
        val case = StringDerivationOracleCase()
        val evidence =
            ValueTransferEvidence.StringInterpolation.fromCompiler(
                    templateRange = case.template,
                    entryRange = flowRange(70, 90),
                    entryIndex = ValueInterpolationEntryIndex.parse(1).flowRefined(),
                    scalarKind = ValueInterpolationScalarKind.STRING,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                source = case.source,
                target = case.target,
                kind = ValueTransferKind.DERIVATION,
                evidence = evidence,
            ),
        )
    }

    @Test
    fun `a field path and non-scalar source role cannot become a whole template`() {
        val case = StringDerivationOracleCase()
        val path = flowPath(ValuePathSegment.Field(flowField("Request.secret", 500, 520)))
        case.reject(source = case.source.withState(valuePath = path).flowRefined())
        case.reject(target = case.target.withState(valuePath = path).flowRefined())
        case.reject(
            source = case.source.withState(valuePath = path).flowRefined(),
            target = case.target.withState(valuePath = path).flowRefined(),
        )
        case.reject(source = case.site(case.source.range, ValueRole.Return))
        case.reject(target = case.site(case.template, ValueRole.LocalBinding))
    }
}
