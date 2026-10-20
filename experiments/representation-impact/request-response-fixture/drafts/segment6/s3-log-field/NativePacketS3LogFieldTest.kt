package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind

/** The original logging seed keeps distinct guarded fields before the scalar contributes to its actual template. */
class NativePacketS3LogFieldTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-s3-log-field-jdk"

    fun testOriginalLogHelperHasActualBranchFieldsAndImmediateScalarTemplateEntries() {
        val fixture = fixture()
        val facts = facts(fixture)
        assertFalse(facts.success.payload.compilerIdentity == facts.error.payload.compilerIdentity)
        assertFalse(facts.success.field.compilerIdentity == facts.error.field.compilerIdentity)
        assertFalse(facts.success.type.compilerIdentity == facts.error.type.compilerIdentity)
        assertFalse(facts.success.templateRange == facts.error.templateRange)
        println("S3_LOG_FIELD_TYPES k2=ASSERTED derivation=NOT_RUN")
    }

    fun testOriginalSourceHelperProjectsBothFieldsWithExactScopedSubtypeSubjects() {
        val fixture = fixture()
        val facts = facts(fixture)
        val source = source(fixture)
        val entry = source.entry()
        val step = source.read(entry.formal)
        val fields = step.transfers.filter { it.kind == ValueTransferKind.FIELD_READ }
        assertEquals(setOf(facts.success.valueRange, facts.error.valueRange), fields.map { it.target.range }.toSet())
        assertPayload(fields.single { it.target.range == facts.success.valueRange }, facts.success, entry)
        assertPayload(fields.single { it.target.range == facts.error.valueRange }, facts.error, entry)
        val scalar = source.scalar(entry, facts.success)
        assertEquals(ValueAccessPath.Whole, scalar.valuePath)
        assertEquals(facts.success.scalarRange, scalar.range)
        println("S3_LOG_SCOPED_FIELDS fields_and_predicates=ASSERTED derivation=NOT_RUN")
    }

    fun testOriginalStatusCodeContributesOnlyToItsActualStatusTemplate() {
        val fixture = fixture()
        val facts = facts(fixture)
        assertDerivation(source(fixture), facts.success, facts.error)
    }

    fun testOriginalErrorCodeContributesOnlyToItsActualCodeTemplate() {
        val fixture = fixture()
        val facts = facts(fixture)
        assertDerivation(source(fixture), facts.error, facts.success)
    }

    fun testAnOriginalScalarCannotReachTheSiblingTemplateOrAConstantEntry() {
        val fixture = fixture()
        val facts = facts(fixture)
        val source = source(fixture)
        val entry = source.entry()
        val scalar = source.scalar(entry, facts.success)
        val read = source.read(scalar)
        assertTrue(
            read.transfers.none { transfer ->
                transfer.target.range == facts.error.templateRange ||
                    transfer.target.range == facts.success.literalRange
            }
        )
        assertTrue(read.applications.isEmpty())
        println("S3_LOG_UNRELATED sibling_and_literal=NO_EMITTED_WITNESS completion=NOT_ASSERTED")
    }

    fun testTheOriginalWhenSubjectCannotJumpToTheWhenResultOrEitherTemplate() {
        val fixture = fixture()
        val facts = facts(fixture)
        val source = source(fixture)
        val entry = source.entry()
        val formal = source.read(entry.formal)
        val subject =
            formal.transfers.single { transfer ->
                transfer.kind == ValueTransferKind.LOCAL_READ && transfer.target.range == facts.subjectRange
            }
        assertEquals(ValueAccessPath.Whole, subject.target.valuePath)
        val read = source.read(subject.target)
        val forbidden = setOf(facts.whenRange, facts.success.templateRange, facts.error.templateRange)
        assertTrue(read.transfers.none { transfer -> transfer.target.range in forbidden })
        assertTrue(read.applications.isEmpty())
        println("S3_LOG_SUBJECT direct_subject_to_result=NO_EMITTED_WITNESS completion=NOT_ASSERTED")
    }

    private fun assertDerivation(
        source: NativePacketS3LogFieldSource,
        expected: S3LogBranchFacts,
        sibling: S3LogBranchFacts,
    ) {
        val entry = source.entry()
        val scalar = source.scalar(entry, expected)
        assertEquals(ValueAccessPath.Whole, scalar.valuePath)
        val result = source.read(scalar)
        val derived = result.transfers.filter { it.target.range == expected.templateRange }
        assertEquals(
            "Original scalar ${expected.scalarRange} needs its exact template contribution. " +
                "Causes=${result.obligations.map { it.cause }}. Branch/helper/callback/logger=NOT_REACHED",
            1,
            derived.size,
        )
        val transfer = derived.single()
        assertEquals(scalar, transfer.source)
        assertEquals(expected.templateRange, transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(ValueAccessPath.Whole, transfer.target.valuePath)
        assertEquals(scalar.enclosing, transfer.target.enclosing)
        assertEquals(scalar.executionOwner, transfer.target.executionOwner)
        assertEquals(scalar.callStack, transfer.target.callStack)
        assertEquals(scalar.predicates, transfer.target.predicates)
        assertTrue(result.applications.isEmpty())
        assertTrue(
            result.transfers.none { candidate ->
                candidate.target.range == sibling.templateRange || candidate.target.range == expected.literalRange
            }
        )
        println("S3_LOG_DERIVATION immediate_scalar_to_template=ASSERTED later_logger_route=NOT_RUN")
    }

    private fun assertPayload(transfer: ValueTransfer, expected: S3LogBranchFacts, entry: S3LogFieldEntry) {
        assertEquals(entry.formal, transfer.source)
        val guarded = transfer.evidence as ValueTransferEvidence.Guarded
        val operation = (guarded.delegate as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals(expected.payload.compilerIdentity, operation.field.declaration.compilerIdentity)
        assertEquals(expected.payload.range, operation.field.declaration.range)
        val added = guarded.added.entries.filterIsInstance<ValuePredicate.Subtype>()
        assertEquals(1, added.size)
        val predicate = added.single()
        assertEquals(expected.type.compilerIdentity, predicate.resolvedType.compilerIdentity)
        assertEquals(expected.type.range, predicate.resolvedType.range)
        assertEquals(
            entry.receiver
                .withState(valuePath = ValueAccessPath.Whole, predicates = ValuePredicates.Empty)
                .nativeRefined(),
            predicate.subject.occurrence,
        )
        assertEquals(entry.formal.callStack, transfer.target.callStack)
        assertEquals(entry.formal.executionOwner, transfer.target.executionOwner)
        assertEquals(entry.formal.predicates.entries + added, transfer.target.predicates.entries)
    }

    private fun fixture() = packetFixture(NativePacketCompositeSuccessSource.PATHS)

    private fun facts(fixture: NativePacketValueFixture) =
        NativePacketS3LogFieldFacts(project, getOrCreateProjectBaseDir()).read(fixture)

    private fun source(fixture: NativePacketValueFixture) =
        NativePacketS3LogFieldSource(project, getOrCreateProjectBaseDir(), fixture)
}
