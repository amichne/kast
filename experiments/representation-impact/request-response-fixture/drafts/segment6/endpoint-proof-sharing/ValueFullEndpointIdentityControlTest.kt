package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSelectorIssueFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

/** Full selector proof differs from object equality and from a declaration's value identity. */
class ValueFullEndpointIdentityControlTest {
    @Test
    fun `full equal subject selectors keep fingerprints and all semantic facts`(reporter: TestReporter) {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.SUBJECT)
        val before = f.fresh
        assertNotSame(f.prior.source.enclosing, before.source.enclosing)
        assertNotEquals(f.prior.source.enclosing, before.source.enclosing)
        assertFullProof(f.prior.source.enclosing, before.source.enclosing)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(before, after, reporter)
        assertConserved(before, after)
    }

    @Test
    fun `full selector sharing compacts the source without replacing it with a resolved endpoint`(
        reporter: TestReporter
    ) {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.SUBJECT)
        val before = f.fresh
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(before, after, reporter)
        assertConserved(before, after)
        assertSame(f.canonical.getValue("host"), after.source.enclosing)
        assertSame(after.source.enclosing, after.source.executionOwner.enclosing)
        assertInstanceOf(RelationEndpoint.Subject::class.java, after.source.enclosing)
        assertTrue(after.source.retainedBytes < before.source.retainedBytes)
        assertTrue(after.retainedBytes < before.retainedBytes)
    }

    @Test
    fun `a declaration-kind constraint cannot borrow an unrestricted endpoint`() {
        val kinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.FUNCTION)).flowRefined()
        val constraints = SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds)
        for (variant in EndpointSharingVariant.entries) {
            val f = ValueEndpointSharingCase(variant)
            val before = f.observation(hostConstraints = constraints)
            assertEquals(f.prior.source.enclosing.valueIdentity, before.source.enclosing.valueIdentity)
            assertNotEquals(f.prior.source.enclosing.constraints, before.source.enclosing.constraints)
            assertNotEquals(f.prior.source.enclosing.fingerprint, before.source.enclosing.fingerprint)
            val after = before.shareCallableEvidence(listOf(f.prior))
            assertNotSame(f.prior.source.enclosing, after.source.enclosing)
            assertConserved(before, after)
        }
    }

    @Test
    fun `another semantic basis cannot borrow an older full proof`() {
        for (variant in EndpointSharingVariant.entries) {
            val old = ValueEndpointSharingCase(variant)
            val before = ValueEndpointSharingCase(variant, generation = 2).fresh
            assertEquals(old.prior.source.enclosing.valueIdentity, before.source.enclosing.valueIdentity)
            assertNotEquals(old.prior.source.basis, before.source.basis)
            val after = before.shareCallableEvidence(listOf(old.prior))
            assertNotSame(old.prior.source.enclosing, after.source.enclosing)
            assertConserved(before, after)
        }
    }

    @Test
    fun `subject and resolved representations cannot replace one another`() {
        for (variant in EndpointSharingVariant.entries) {
            val f = ValueEndpointSharingCase(variant)
            val other =
                if (variant == EndpointSharingVariant.SUBJECT) EndpointSharingVariant.RESOLVED
                else EndpointSharingVariant.SUBJECT
            val before = f.observation(actualVariant = other)
            assertEquals(f.prior.source.enclosing.valueIdentity, before.source.enclosing.valueIdentity)
            assertNotEquals(f.prior.source.enclosing.javaClass, before.source.enclosing.javaClass)
            val after = before.shareCallableEvidence(listOf(f.prior))
            assertNotSame(f.prior.source.enclosing, after.source.enclosing)
            assertConserved(before, after)
        }
    }

    @Test
    fun `selector restoration rejects a foreign scope under an unchanged fingerprint`() {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.SUBJECT)
        val subject = f.prior.source.enclosing as RelationEndpoint.Subject
        val scope = (subject.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE)
        assertNotEquals(subject.scope, scope)
        assertEquals(
            Refinement.Rejected(SymbolSelectorIssueFailure.FINGERPRINT_MISMATCH),
            SymbolSelector.restore(
                subject.lease,
                scope,
                CompilerGroundedSymbolEvidence.fromSelector(subject.selector),
                subject.selector.fingerprint,
                subject.constraints,
            ),
        )
    }

    private fun assertConserved(before: ValueFlowStep, after: ValueFlowStep) {
        assertEquals(before.source.identity, after.source.identity)
        assertEquals(before.transfers, after.transfers)
        assertEquals(before.obligations, after.obligations)
        assertEquals(before.source.valuePath, after.source.valuePath)
        assertEquals(before.source.callStack, after.source.callStack)
        assertEquals(before.source.predicates, after.source.predicates)
        assertEquals(before.examinedWorkUnits, after.examinedWorkUnits)
        assertEquals(before.terminal, after.terminal)
        assertSame(before.domain, after.domain)
        assertSame(before.receipts, after.receipts)
        val expected = ValueEndpointSharingInventory(before)
        val actual = ValueEndpointSharingInventory(after)
        assertEquals(expected.references.size, actual.references.size)
        expected.references.zip(actual.references).forEach { (first, second) -> assertFullProof(first, second) }
    }

    private fun assertFullProof(expected: RelationEndpoint, actual: RelationEndpoint) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.lease, actual.lease)
        assertEquals(expected.scope, actual.scope)
        assertEquals(expected.constraints, actual.constraints)
        assertEquals(expected.file, actual.file)
        assertEquals(expected.range, actual.range)
        assertEquals(expected.name, actual.name)
        assertEquals(expected.qualifiedIdentity, actual.qualifiedIdentity)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.signature, actual.signature)
        assertEquals(expected.compilerIdentity, actual.compilerIdentity)
        assertEquals(expected.fingerprint, actual.fingerprint)
        assertEquals(expected.valueIdentity, actual.valueIdentity)
        assertEquals(expected.valueExecutionOwner.identity, actual.valueExecutionOwner.identity)
        when (expected) {
            is RelationEndpoint.Resolved ->
                assertEquals(
                    expected.evidence,
                    assertInstanceOf(RelationEndpoint.Resolved::class.java, actual).evidence,
                )
            is RelationEndpoint.Subject ->
                assertEquals(
                    expected.selector.fingerprint,
                    assertInstanceOf(RelationEndpoint.Subject::class.java, actual).selector.fingerprint,
                )
        }
    }

    private fun record(before: ValueFlowStep, after: ValueFlowStep, reporter: TestReporter) {
        reporter.publishEntry("before.sourceBytes", before.source.retainedBytes.toString())
        reporter.publishEntry("after.sourceBytes", after.source.retainedBytes.toString())
        reporter.publishEntry("before.stepBytes", before.retainedBytes.toString())
        reporter.publishEntry("after.stepBytes", after.retainedBytes.toString())
        reporter.publishEntry(
            "before.physicalEndpoints",
            ValueEndpointSharingInventory(before).physicalEndpoints.size.toString(),
        )
        reporter.publishEntry(
            "after.physicalEndpoints",
            ValueEndpointSharingInventory(after).physicalEndpoints.size.toString(),
        )
    }
}
