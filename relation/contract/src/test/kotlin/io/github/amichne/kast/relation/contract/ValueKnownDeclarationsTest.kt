package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueKnownDeclarationsTest {
    @Test
    fun `empty admission is canonical and carries no catalog completeness claim`() {
        val owner = endpoint()
        val empty = ValueKnownDeclarations.admit(emptyList(), owner.lease).flowRefined()
        assertSame(ValueKnownDeclarations.Empty, empty)
        assertTrue(empty.belongsTo(owner.lease))
        assertTrue(empty.hasSameProofAs(ValueKnownDeclarations.Empty))
        assertEquals(emptyList<RevalidatedRelationEndpoint>(), empty.declarations)
    }

    @Test
    fun `ordered duplicates keep original wrappers in an immutable snapshot`() {
        val first = revalidated(endpoint())
        val second = revalidated(endpoint("Other", 300))
        val supplied = mutableListOf(first, second, first)
        val known = ValueKnownDeclarations.admit(supplied, first.endpoint.lease).flowRefined()
        supplied.clear()
        assertEquals(listOf(first, second, first), known.declarations)
        assertSame(first, known.declarations[0])
        assertSame(second, known.declarations[1])
        assertSame(first, known.declarations[2])
        assertThrows(UnsupportedOperationException::class.java) {
            (known.declarations as MutableList<RevalidatedRelationEndpoint>).clear()
        }
    }

    @Test
    fun `the size limit counts every supplied entry and foreign authority rejects`() {
        val proof = revalidated(endpoint())
        val full = List(128) { proof }
        assertEquals(128, ValueKnownDeclarations.admit(full, proof.endpoint.lease).flowRefined().declarations.size)
        assertEquals(
            Refinement.Rejected(ValueKnownDeclarationsFailure.TOO_MANY_DECLARATIONS),
            ValueKnownDeclarations.admit(full + proof, proof.endpoint.lease),
        )
        val foreign = revalidated(flowEndpoint("Known", 0, 200, generation = 2) as RelationEndpoint.Resolved)
        assertEquals(
            Refinement.Rejected(ValueKnownDeclarationsFailure.FOREIGN_BASIS),
            ValueKnownDeclarations.admit(listOf(foreign), proof.endpoint.lease),
        )
        assertFalse(known(proof).belongsTo(foreign.endpoint.lease))
    }

    @Test
    fun `equal distinct full proofs match but order and representation variant stay exact`() {
        val first = revalidated(endpoint())
        val equal = revalidated(endpoint())
        assertNotSame(first.endpoint, equal.endpoint)
        assertTrue(known(first).hasSameProofAs(known(equal)))
        val resolved = first.endpoint as RelationEndpoint.Resolved
        val subject = RelationEndpoint.subject(SymbolSelector.issue(resolved.lease, resolved.scope, resolved.evidence))
        val selected = RevalidatedRelationEndpoint.validate(subject, resolved.evidence).flowRefined()
        assertTrue(resolved.hasSameProofAs(subject))
        assertFalse(known(first).hasSameProofAs(known(selected)))
        val second = revalidated(endpoint("Other", 300))
        assertFalse(known(first, second).hasSameProofAs(known(second, first)))
        assertFalse(known(first).hasSameProofAs(known(first, first)))
    }

    @Test
    fun `the same declaration identity cannot substitute scope or constraints`() {
        val original = endpoint()
        val changedScope =
            RelationEndpoint.resolve(
                    original.lease,
                    (original.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
                    original.evidence,
                )
                .flowRefined() as RelationEndpoint.Resolved
        val kinds = SymbolDiscoveryDeclarationKinds.from(setOf(original.kind)).flowRefined()
        val changedConstraints =
            RelationEndpoint.resolve(
                    original.lease,
                    original.scope,
                    original.evidence,
                    SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds),
                )
                .flowRefined() as RelationEndpoint.Resolved
        for (substitute in listOf(changedScope, changedConstraints)) {
            assertEquals(original.valueIdentity, substitute.valueIdentity)
            assertFalse(known(revalidated(original)).hasSameProofAs(known(revalidated(substitute))))
        }
    }

    private fun endpoint(name: String = "Known", start: Int = 0) =
        flowEndpoint(name, start, start + 200) as RelationEndpoint.Resolved

    private fun revalidated(endpoint: RelationEndpoint.Resolved) =
        RevalidatedRelationEndpoint.validate(endpoint, endpoint.evidence).flowRefined()

    private fun known(vararg values: RevalidatedRelationEndpoint) =
        ValueKnownDeclarations.admit(values.toList(), values.first().endpoint.lease).flowRefined()
}
