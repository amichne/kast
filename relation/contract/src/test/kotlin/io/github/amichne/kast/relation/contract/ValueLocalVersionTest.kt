package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueLocalVersionTest {
    @Test
    fun `stable local refinement preserves exact version use and resumable identity`() {
        val owner = flowEndpoint("local", 0, 200)
        val source = binding(owner, version(owner))
        val use = flowRange(35, 36)
        val target = refinedRead(source)
        val fact = target.conditions.facts.single() as ValueCondition.StableLocal
        val transfer = ValueTransfer.fromCompiler(source, target, ValueTransferKind.LOCAL_READ).flowRefined()
        assertEquals(source.localVersion, fact.version)
        assertTrue(fact.origin.matches(source))
        assertEquals(use, fact.useRange)
        assertSame(target.conditions, transfer.target.conditions)
        assertEquals(
            setOf(target.identity),
            LocalBindingReadRemainder.admit(
                    source,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    emptySet(),
                    setOf(target.identity),
                )
                .flowRefined()
                .emitted,
        )
        val erased = ValueSite.fromCompiler(owner, use, ValueRole.LocalRead).flowRefined()
        assertNotEquals(target.identity, erased.identity)
        assertNotEquals(buildString { site(target) }, buildString { site(erased) })
        assertTrue(target.retainedBytes > erased.retainedBytes)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.CONDITIONS_NOT_PRESERVED),
            target.withState(conditions = ValueConditions.Unconditional),
        )
    }

    @Test
    fun `read remainder charges its condition payload rather than the unqualified source`() {
        val owner = flowEndpoint("local", 0, 200)
        val source = binding(owner, version(owner))
        val target = refinedRead(source)
        val longer = refinedRead(source, "fixture." + "X".repeat(1000))
        val shortRemainder =
            LocalBindingReadRemainder.admit(
                    source,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    emptySet(),
                    setOf(target.identity),
                )
                .flowRefined()
        val longRemainder =
            LocalBindingReadRemainder.admit(
                    source,
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    emptySet(),
                    setOf(longer.identity),
                )
                .flowRefined()
        assertEquals(2L * (1008 - "fixture.Payload".length), longRemainder.retainedBytes - shortRemainder.retainedBytes)
        assertNotEquals(buildString { site(target) }, buildString { site(longer) })
    }

    @Test
    fun `stable local fact cannot be borrowed by another use or unversioned binding`() {
        val owner = flowEndpoint("local", 0, 200)
        val source = binding(owner, version(owner))
        val target = refinedRead(source)
        val fact = target.conditions.facts.single() as ValueCondition.StableLocal
        val wrongUse =
            ValueSite.fromCompiler(owner, flowRange(40, 41), ValueRole.LocalRead, conditions = target.conditions)
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, wrongUse, ValueTransferKind.LOCAL_READ),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            LocalBindingReadRemainder.admit(
                source,
                RelationSearchBoundary.RETAINED_SUBJECT,
                emptySet(),
                setOf(wrongUse.identity),
            ),
        )
        for (range in listOf(flowRange(20, 21), flowRange(60, 61), flowRange(90, 91))) {
            assertEquals(
                Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER),
                ValueCondition.StableLocal.fromCompiler(source, range, fact.originalType, fact.refinedType),
            )
        }
        val unversioned = ValueSite.fromCompiler(owner, source.range, source.role).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.StableLocal.fromCompiler(unversioned, fact.useRange, fact.originalType, fact.refinedType),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(unversioned, target, ValueTransferKind.LOCAL_READ),
        )
    }

    @Test
    fun `next write RHS belongs to the old version but its LHS and later reads do not`() {
        val owner = flowEndpoint("local", 0, 200)
        val version = version(owner)
        val source = binding(owner, version)
        for (range in listOf(flowRange(35, 36), flowRange(70, 71))) {
            val read = ValueSite.fromCompiler(owner, range, ValueRole.LocalRead).flowRefined()
            assertTrue(ValueTransfer.fromCompiler(source, read, ValueTransferKind.LOCAL_READ) is Refinement.Refined)
        }
        for (range in listOf(flowRange(20, 21), flowRange(60, 61), flowRange(90, 91))) {
            val read = ValueSite.fromCompiler(owner, range, ValueRole.LocalRead).flowRefined()
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(source, read, ValueTransferKind.LOCAL_READ),
            )
        }
    }

    @Test
    fun `version identity lifetime and storage survive state sharing and distinguish declaration-only claims`() {
        val owner = flowEndpoint("local", 0, 200)
        val first = binding(owner, version(owner))
        val unversioned = ValueSite.fromCompiler(owner, first.range, ValueRole.LocalBinding).flowRefined()
        val secondProof =
            ValueLocalVersion.Defined.fromCompiler(
                    first.executionOwner,
                    flowRange(10, 30),
                    flowRange(5, 195),
                    flowRange(60, 80),
                    flowRange(70, 80),
                    ValueLocalVersion.End.BlockEnd(flowRange(5, 195)),
                )
                .flowRefined()
        val second = binding(owner, secondProof)
        assertNotEquals(first.identity, second.identity)
        assertNotEquals(first.identity, unversioned.identity)
        assertNotEquals(buildString { site(first) }, buildString { site(unversioned) })
        assertSame(first.localVersion, first.withState().flowRefined().localVersion)
        assertTrue(first.retainedBytes > unversioned.retainedBytes)
        assertFalse(secondProof.admitsRead(flowRange(70, 71)))
        assertTrue(secondProof.admitsRead(flowRange(90, 91)))
    }

    @Test
    fun `proof cannot be attached to a foreign authority role anchor or owner`() {
        val owner = flowEndpoint("local", 0, 200)
        val version = version(owner)
        for (foreign in listOf(flowEndpoint("other", 0, 200), flowEndpoint("local", 0, 200, generation = 2))) {
            assertEquals(
                Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
                ValueSite.fromCompiler(foreign, version.definition, ValueRole.LocalBinding, localVersion = version),
            )
        }
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            ValueSite.fromCompiler(owner, version.definition, ValueRole.LocalRead, localVersion = version),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            ValueSite.fromCompiler(owner, flowRange(60, 80), ValueRole.LocalBinding, localVersion = version),
        )
    }

    @Test
    fun `invalid lifetime and RHS anchors reject rather than manufacturing a version`() {
        val owner = flowEndpoint("local", 0, 200)
        for (end in
            listOf(
                ValueLocalVersion.End.NextWrite(flowRange(20, 80), flowRange(70, 80)),
                ValueLocalVersion.End.NextWrite(flowRange(60, 80), flowRange(90, 100)),
                ValueLocalVersion.End.BlockEnd(flowRange(5, 150)),
            )) {
            assertEquals(
                Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER),
                ValueLocalVersion.Defined.fromCompiler(
                    ValueExecutionOwner.declaration(owner),
                    flowRange(10, 30),
                    flowRange(5, 195),
                    flowRange(10, 30),
                    flowRange(20, 30),
                    end,
                ),
            )
        }
    }

    @Test
    fun `only the defining RHS can supply a versioned binding`() {
        val owner = flowEndpoint("local", 0, 200)
        val target = binding(owner, version(owner))
        val rhs = ValueSite.fromCompiler(owner, flowRange(20, 30), ValueRole.ExpressionResult).flowRefined()
        assertTrue(ValueTransfer.fromCompiler(rhs, target, ValueTransferKind.LOCAL_BINDING) is Refinement.Refined)
        val unrelated = ValueSite.fromCompiler(owner, flowRange(35, 36), ValueRole.LocalRead).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(unrelated, target, ValueTransferKind.LOCAL_BINDING),
        )
    }

    @Test
    fun `implicit component result at the entry anchor supplies only that versioned entry`() {
        val owner = flowEndpoint("local", 0, 200)
        val entry = flowRange(10, 15)
        val version =
            ValueLocalVersion.Defined.fromCompiler(
                    execution = ValueExecutionOwner.declaration(owner),
                    declaration = entry,
                    block = flowRange(5, 195),
                    definition = entry,
                    rhs = entry,
                    end = ValueLocalVersion.End.NextWrite(flowRange(60, 80), flowRange(70, 80)),
                )
                .flowRefined()
        val target = binding(owner, version)
        val result = ValueSite.fromCompiler(owner, entry, ValueRole.ExpressionResult).flowRefined()
        val transfer = ValueTransfer.fromCompiler(result, target, ValueTransferKind.LOCAL_BINDING).flowRefined()
        assertEquals(result, transfer.source)
        assertEquals(target, transfer.target)
        assertNotEquals(result.identity, target.identity)
        assertEquals(entry, version.declaration)
        assertTrue(version.admitsRead(flowRange(45, 46)))
        assertTrue(version.admitsRead(flowRange(72, 73)))
        assertFalse(version.admitsRead(flowRange(90, 91)))
        for (foreign in listOf(flowRange(20, 25), flowRange(30, 40))) {
            val other = ValueSite.fromCompiler(owner, foreign, ValueRole.ExpressionResult).flowRefined()
            assertEquals(
                Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
                ValueTransfer.fromCompiler(other, target, ValueTransferKind.LOCAL_BINDING),
            )
        }
    }

    @Test
    fun `whole destructuring initializer cannot masquerade as an entry-local result anchor`() {
        val owner = flowEndpoint("local", 0, 200)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.ANCHOR_OUTSIDE_OWNER),
            ValueLocalVersion.Defined.fromCompiler(
                execution = ValueExecutionOwner.declaration(owner),
                declaration = flowRange(10, 15),
                block = flowRange(5, 195),
                definition = flowRange(10, 15),
                rhs = flowRange(30, 40),
                end = ValueLocalVersion.End.BlockEnd(flowRange(5, 195)),
            ),
        )
    }

    @Test
    fun `remainder excludes emitted reads of a different definition`() {
        val owner = flowEndpoint("local", 0, 200)
        val source = binding(owner, version(owner))
        val admitted = ValueSite.fromCompiler(owner, flowRange(35, 36), ValueRole.LocalRead).flowRefined()
        val forbidden = ValueSite.fromCompiler(owner, flowRange(90, 91), ValueRole.LocalRead).flowRefined()
        assertTrue(
            LocalBindingReadRemainder.admit(
                source,
                RelationSearchBoundary.RETAINED_SUBJECT,
                emptySet(),
                setOf(admitted.identity),
            ) is Refinement.Refined
        )
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.BASIS_MISMATCH),
            LocalBindingReadRemainder.admit(
                source,
                RelationSearchBoundary.RETAINED_SUBJECT,
                emptySet(),
                setOf(forbidden.identity),
            ),
        )
    }

    private fun version(owner: RelationEndpoint) =
        ValueLocalVersion.Defined.fromCompiler(
                ValueExecutionOwner.declaration(owner),
                flowRange(10, 30),
                flowRange(5, 195),
                flowRange(10, 30),
                flowRange(20, 30),
                ValueLocalVersion.End.NextWrite(flowRange(60, 80), flowRange(70, 80)),
            )
            .flowRefined()

    private fun binding(owner: RelationEndpoint, version: ValueLocalVersion.Defined) =
        ValueSite.fromCompiler(owner, version.definition, ValueRole.LocalBinding, localVersion = version).flowRefined()

    private fun refinedRead(source: ValueSite, type: String = "fixture.Payload"): ValueSite {
        val fact =
            ValueCondition.StableLocal.fromCompiler(
                    source,
                    flowRange(35, 36),
                    CanonicalCompilerType.fromCompiler("kotlin.Any").flowRefined(),
                    CanonicalCompilerType.fromCompiler(type).flowRefined(),
                )
                .flowRefined()
        return ValueSite.fromCompiler(
                source.enclosing,
                fact.useRange,
                ValueRole.LocalRead,
                conditions = source.conditions.requiring(fact).flowRefined(),
            )
            .flowRefined()
    }
}
