package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Signatures and metadata entries derive from the retained K2 capture and exact 2.3.0 source review. */
class ReviewedEntryDeclarationAdmissionTest {
    @Test
    fun `entry getter rejects swapped return type and foreign declaration`() {
        for (target in
            listOf(
                property(name = "key", type = "V"),
                property(name = "value", type = "K"),
                property(name = "key", owner = "fixture.Entry"),
            )) assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admit(target),
        )
    }

    @Test
    fun `entry declaration cannot claim JVM facade or source evidence`() {
        for (file in listOf("kotlin/collections/MapsKt.class", "fixture/Entry.class")) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedOrdinaryLibraryCallable.admit(property(name = "key", file = file)),
            )
        }
    }

    @Test
    fun `component selection rejects pair receiver or foreign facade`() {
        val base = ReviewedValueFixture()
        for (target in
            listOf(
                component(base, 1, receiver = "kotlin/Pair<K,V>"),
                component(base, 2, file = "kotlin/collections/CollectionsKt.class"),
            )) assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admit(target),
        )
    }

    @Test
    fun `exact builtin key declaration has its own reviewed rule`() {
        assertRule(property(name = "key"), "MAP_ENTRY_KEY", "commonMain/kotlin/Collections.kt")
    }

    @Test
    fun `exact builtin value declaration has its own reviewed rule`() {
        assertRule(property(name = "value", type = "V"), "MAP_ENTRY_VALUE", "commonMain/kotlin/Collections.kt")
    }

    @Test
    fun `exact first component has its own reviewed Maps source section`() {
        assertRule(
            component(ReviewedValueFixture(), 1),
            "MAP_ENTRY_COMPONENT1",
            "commonMain/kotlin/collections/Maps.kt",
        )
    }

    @Test
    fun `exact second component has its own reviewed Maps source section`() {
        assertRule(
            component(ReviewedValueFixture(), 2),
            "MAP_ENTRY_COMPONENT2",
            "commonMain/kotlin/collections/Maps.kt",
        )
    }

    private fun assertRule(target: CompilerGroundedSymbolEvidence, operation: String, sourceEntry: String) {
        val selected = ReviewedOrdinaryLibraryCallable.admit(target).flowRefined()
        assertEquals(operation, selected.operation.name)
        val base = ReviewedValueFixture()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    base.digest(ReviewedPositionFixture.BINARY),
                    base.digest(ReviewedPositionFixture.SOURCES),
                )
                .flowRefined()
        assertEquals("KOTLIN_2_3_0_${operation}_V1", artifact.rule.name)
        assertEquals(sourceEntry, artifact.sourceEntry)
    }

    private fun property(
        name: String,
        type: String = "K",
        owner: String = "kotlin.collections.Map.Entry",
        file: String = "kotlin/collections/collections.kotlin_builtins",
    ): CompilerGroundedSymbolEvidence {
        val qualified = "$owner.$name"
        return CompilerGroundedSymbolEvidence.fromBoundary(
                file = external(file),
                rawStartInclusive = 1000,
                rawEndExclusive = 1100,
                rawName = name,
                rawQualifiedIdentity = qualified,
                kind = CompilerSymbolKind.PROPERTY,
                signature =
                    CanonicalCompilerSignature.property(
                            rawQualifiedIdentity = qualified,
                            rawReceiverType = null,
                            rawContextReceiverTypes = emptyList(),
                            rawReturnType = type,
                        )
                        .flowRefined(),
            )
            .flowRefined()
    }

    private fun component(
        base: ReviewedValueFixture,
        index: Int,
        receiver: String = "kotlin/collections/Map.Entry<K,V>",
        file: String = "kotlin/collections/MapsKt.class",
    ): CompilerGroundedSymbolEvidence =
        base.target(
            qualified = "kotlin.collections.component$index",
            receiver = receiver,
            parameters = emptyList(),
            typeParameters = 2,
            file = external(file),
        )

    private fun external(entry: String) =
        SymbolDiscoveryFileIdentity.External(DetachedVirtualFileUrl.parse("jar:///library.jar!/$entry").flowRefined())
}
