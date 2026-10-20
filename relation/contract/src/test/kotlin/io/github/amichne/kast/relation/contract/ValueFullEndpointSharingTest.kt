package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

/** Pure proof and allocation cases establish no native grant recovery or body-summary reuse. */
class ValueFullEndpointSharingTest {
    @Test
    fun `distinct resolved endpoint proofs keep every fact and receipt through sharing`(reporter: TestReporter) {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.RESOLVED)
        val before = f.fresh
        assertFullProof(f.prior.source.enclosing, before.source.enclosing)
        assertNotSame(f.prior.source.enclosing, before.source.enclosing)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(before, after, reporter)
        assertConserved(before, after)
    }

    @Test
    fun `full admitted endpoint sharing compacts the source before its next native read`(reporter: TestReporter) {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.RESOLVED)
        val before = f.fresh
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(before, after, reporter)
        assertConserved(before, after)
        assertSame(f.canonical.getValue("host"), after.source.enclosing)
        assertSame(after.source.enclosing, after.source.executionOwner.enclosing)
        assertTrue(after.source.retainedBytes < before.source.retainedBytes)
        assertTrue(after.retainedBytes < before.retainedBytes)
    }

    @Test
    fun `the same declaration with another full scope cannot borrow the prior endpoint`(reporter: TestReporter) {
        val f = ValueEndpointSharingCase(EndpointSharingVariant.RESOLVED)
        val original = f.prior.source.enclosing
        val changed = (original.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE)
        val before = f.observation(hostScope = changed)
        assertNotEquals(original.scope, before.source.enclosing.scope)
        assertEquals(original.valueIdentity, before.source.enclosing.valueIdentity)
        assertNotEquals(original.fingerprint, before.source.enclosing.fingerprint)
        val after = before.shareCallableEvidence(listOf(f.prior))
        record(before, after, reporter)
        assertNotSame(original, after.source.enclosing)
        assertConserved(before, after)
    }

    private fun assertConserved(before: ValueFlowStep, after: ValueFlowStep) {
        assertEquals(before.source.identity, after.source.identity)
        assertEquals(before.source.range, after.source.range)
        assertEquals(before.source.valuePath, after.source.valuePath)
        assertEquals(before.source.predicates, after.source.predicates)
        assertEquals(before.source.callStack, after.source.callStack)
        assertEquals(before.transfers, after.transfers)
        assertEquals(before.obligations, after.obligations)
        assertEquals(before.applications, after.applications)
        assertEquals(before.terminal, after.terminal)
        assertEquals(before.examinedWorkUnits, after.examinedWorkUnits)
        assertSame(before.receipts, after.receipts)
        assertSame(before.domain, after.domain)
        val expected = ValueEndpointSharingInventory(before)
        val actual = ValueEndpointSharingInventory(after)
        assertEquals(4, expected.siteCount)
        assertEquals(3, expected.frameCount)
        assertEquals(expected.siteCount, actual.siteCount)
        assertEquals(expected.frameCount, actual.frameCount)
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
