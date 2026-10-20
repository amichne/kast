package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** Supplemental real K2 scalars qualify the primitive. They do not replace the original S3 acceptance seed. */
class NativePacketStringInterpolationControlTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-string-interpolation-control-jdk"

    fun testAnActualNonnullableStringContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.STRING)

    fun testAnActualNonnullableBooleanContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.BOOLEAN)

    fun testAnActualNonnullableCharContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.CHAR)

    fun testAnActualNonnullableByteContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.BYTE)

    fun testAnActualNonnullableShortContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.SHORT)

    fun testAnActualNonnullableIntContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.INT)

    fun testAnActualNonnullableLongContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.LONG)

    fun testAnActualNonnullableFloatContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.FLOAT)

    fun testAnActualNonnullableDoubleContributesToItsExactTemplate() = assertScalar(InterpolationScalarControl.DOUBLE)

    fun testANullableBuiltinScalarKeepsItsUnsupportedQualification() = assertUnsupported("nullableValue")

    fun testACustomSourceToStringDoesNotGrantTheBuiltinScalarRule() = assertUnsupported("customValue")

    fun testAGenuineTypeParameterDoesNotGrantTheBuiltinScalarRule() = assertUnsupported("genericValue")

    fun testARealJavaFlexibleStringDoesNotGrantTheDirectClassRule() = assertUnsupported("flexibleValue")

    fun testATrackedStringFieldCannotBecomeTheWholeTemplateValue() {
        val fixture = fixture()
        val facts = facts()
        val expected = facts.nested(fixture)
        val source = source(fixture)
        val scalar = source.resolve(expected.input)
        val field = facts.lengthField(fixture)
        val path = ValueAccessPath.fromSegments(listOf(ValuePathSegment.Field(field))).nativeRefined()
        val tracked = scalar.withState(valuePath = path).nativeRefined()
        assertEquals(scalar.range, tracked.range)
        assertEquals(path, tracked.valuePath)
        assertUnsupportedRead(source.read(tracked), expected)
    }

    fun testTheActualScalarCannotReachASiblingTemplateOrItsConstantEntry() {
        val fixture = fixture()
        val expected = facts().sibling(fixture)
        assertNotNull("The separate sibling template must exist before a forbidden assertion", expected.sibling)
        val source = source(fixture)
        val scalar = source.resolve(expected.input)
        val read = source.read(scalar)
        assertDerivation(source = scalar, step = read, expected = expected, control = InterpolationScalarControl.STRING)
        assertTrue(read.transfers.none { transfer -> transfer.target.range == expected.sibling })
        assertTrue(read.transfers.none { transfer -> transfer.target.range == expected.literal })
    }

    fun testAGenuineInputReferenceCannotJumpToTheLaterConstantResult() {
        val fixture = fixture()
        val (input, constant) = facts().constant(fixture)
        val source = source(fixture)
        val scalar = source.resolve(input)
        val read = source.read(scalar)
        assertEquals(ValueAccessPath.Whole, scalar.valuePath)
        assertTrue(read.transfers.none { transfer -> transfer.kind == ValueTransferKind.DERIVATION })
        assertTrue(read.transfers.none { transfer -> transfer.target.range == constant })
        assertTrue(read.applications.isEmpty())
    }

    private fun assertScalar(control: InterpolationScalarControl) {
        val fixture = fixture()
        val expected = facts().scalar(fixture, control)
        val source = source(fixture)
        val scalar = source.resolve(expected.input)
        assertDerivation(source = scalar, step = source.read(scalar), expected = expected, control = control)
    }

    private fun assertDerivation(
        source: ValueSite,
        step: ValueFlowStep,
        expected: InterpolationControlFacts,
        control: InterpolationScalarControl,
    ) {
        assertEquals(ValueRole.ExpressionResult, source.role)
        assertEquals(ValueAccessPath.Whole, source.valuePath)
        val derivatives = step.transfers.filter { it.kind == ValueTransferKind.DERIVATION }
        assertEquals(
            "The actual ${control.qualifiedType} scalar needs exactly its own template contribution",
            1,
            derivatives.size,
        )
        val transfer = derivatives.single()
        assertEquals(source, transfer.source)
        assertEquals(expected.template, transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(ValueAccessPath.Whole, transfer.target.valuePath)
        assertEquals(source.enclosing, transfer.target.enclosing)
        assertEquals(source.executionOwner, transfer.target.executionOwner)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(source.predicates, transfer.target.predicates)
        val evidence = transfer.evidence as ValueTransferEvidence.StringInterpolation
        assertEquals(expected.template, evidence.templateRange)
        assertEquals(expected.entry, evidence.entryRange)
        assertEquals(expected.entryIndex, evidence.entryIndex.value)
        assertEquals(control.kind, evidence.scalarKind)
        assertTrue(step.applications.isEmpty())
        assertTrue(step.obligations.isEmpty())
    }

    private fun assertUnsupported(method: String) {
        val fixture = fixture()
        val expected = facts().unsupported(fixture, method)
        val source = source(fixture)
        val scalar = source.resolve(expected.input)
        assertEquals(ValueRole.ExpressionResult, scalar.role)
        assertEquals(ValueAccessPath.Whole, scalar.valuePath)
        assertUnsupportedRead(source.read(scalar), expected)
    }

    private fun assertUnsupportedRead(step: ValueFlowStep, expected: InterpolationControlFacts) {
        assertTrue(step.transfers.none { transfer -> transfer.kind == ValueTransferKind.DERIVATION })
        assertTrue(step.transfers.none { transfer -> transfer.target.range == expected.template })
        assertTrue(step.applications.isEmpty())
        assertTrue(
            "Unsupported interpolation must keep an explicit finite qualification",
            step.obligations.any { obligation -> obligation.cause == ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION },
        )
    }

    private fun fixture() = packetFixture(listOf(NativePacketStringInterpolationControlSource.PATH))

    private fun facts() = NativePacketStringInterpolationControlFacts(project, getOrCreateProjectBaseDir())

    private fun source(fixture: NativePacketValueFixture) =
        NativePacketStringInterpolationControlSource(project, fixture)
}
