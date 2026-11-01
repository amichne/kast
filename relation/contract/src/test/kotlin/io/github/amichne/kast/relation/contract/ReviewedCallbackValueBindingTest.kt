package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReviewedCallbackValueBindingTest {
    @Test
    fun `binding keeps the selected callable formal epoch artifacts and source evidence`() {
        val fixture = ReviewedValueFixture()
        val binding = fixture.binding
        assertSame(fixture.selected, binding.selected)
        assertEquals(fixture.enclosing.lease.identity, binding.basis)
        assertEquals(fixture.body, binding.body)
        assertEquals(fixture.occurrence, binding.occurrence)
        assertEquals(0, binding.position.value)
        assertEquals(ReviewedValueFixture.BINARY_HASH, binding.artifact.binaryArtifact.value)
        assertEquals(ReviewedValueFixture.SOURCE_HASH, binding.artifact.sourceArtifact.value)
        assertEquals("commonMain/generated/_Collections.kt", binding.artifact.sourceEntry)
        assertEquals(
            "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c",
            binding.artifact.sourceEntrySha256,
        )
        assertEquals(
            "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296",
            binding.artifact.implementationSha256,
        )
    }

    @Test
    fun `callable admission rejects source names and unmatched compiler overloads`() {
        val fixture = ReviewedValueFixture()
        val otherClass =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///library.jar!/kotlin/sequences/SequencesKt.class").flowRefined()
            )
        val targets =
            listOf(
                fixture.target(file = fixture.enclosing.file),
                fixture.target(qualified = "kast.repro.map"),
                fixture.target(receiver = "kotlin/sequences/Sequence<T>"),
                fixture.target(receiver = "kotlin/collections/List<T>"),
                fixture.target(receiver = null),
                fixture.target(typeParameters = 1),
                fixture.target(contextReceivers = listOf("kast/repro/Context")),
                fixture.target(file = otherClass),
            )
        for (target in targets) {
            assertEquals(
                ReviewedCallbackValueFailure.CALLABLE_MISMATCH,
                ReviewedLibraryCallable.admit(target, fixture.position(0)).reviewedFailure(),
            )
        }
    }

    @Test
    fun `formal admission rejects another position or function shape`() {
        val fixture = ReviewedValueFixture()
        assertEquals(
            ReviewedCallbackValueFailure.FORMAL_MISMATCH,
            ReviewedLibraryCallable.admit(fixture.target(), fixture.position(1)).reviewedFailure(),
        )
        for (parameters in listOf(emptyList(), listOf("(T)->T"), listOf("(T)->R", "kotlin/Int"))) {
            assertEquals(
                ReviewedCallbackValueFailure.FORMAL_MISMATCH,
                ReviewedLibraryCallable.admit(fixture.target(parameters = parameters), fixture.position(0))
                    .reviewedFailure(),
            )
        }
    }

    @Test
    fun `artifact admission rejects either changed artifact independently`() {
        val fixture = ReviewedValueFixture()
        val changed = fixture.digest("a".repeat(64))
        assertEquals(
            ReviewedCallbackValueFailure.ARTIFACT_MISMATCH,
            ReviewedLibraryArtifact.admit(changed, fixture.digest(ReviewedValueFixture.SOURCE_HASH)).reviewedFailure(),
        )
        assertEquals(
            ReviewedCallbackValueFailure.ARTIFACT_MISMATCH,
            ReviewedLibraryArtifact.admit(fixture.digest(ReviewedValueFixture.BINARY_HASH), changed).reviewedFailure(),
        )
    }

    @Test
    fun `artifact digests reject noncanonical and malformed values`() {
        for (raw in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertEquals(
                ReviewedCallbackValueFailure.ARTIFACT_MISMATCH,
                DependencyArtifactDigest.parse(raw).reviewedFailure(),
            )
        }
    }

    @Test
    fun `binding rejects a different supplier or a callback outside its occurrence`() {
        val fixture = ReviewedValueFixture()
        val other = flowEndpoint("other", 356, 459) as RelationEndpoint.Resolved
        val wrongSupplier = RelationCallableBody.Named.fromCompiler(other.evidence).flowRefined()
        assertEquals(
            ReviewedCallbackValueFailure.OWNER_MISMATCH,
            fixture.binding(supplier = wrongSupplier).reviewedFailure(),
        )
        assertEquals(
            ReviewedCallbackValueFailure.OWNER_MISMATCH,
            fixture.binding(body = fixture.body(410, 420)).reviewedFailure(),
        )
        assertEquals(
            ReviewedCallbackValueFailure.OWNER_MISMATCH,
            fixture.binding(occurrence = fixture.occurrence(350, 459)).reviewedFailure(),
        )
    }
}
