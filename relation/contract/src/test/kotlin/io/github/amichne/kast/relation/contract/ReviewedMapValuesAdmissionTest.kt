package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic compiler facts exercise pure admission; native target capture remains separate evidence. */
class ReviewedMapValuesAdmissionTest {
    @Test
    fun `mapValues name cannot admit another receiver overload`() {
        val fixture = ReviewedPositionFixture()
        for (receiver in listOf("kotlin/collections/Iterable<T>", "kotlin/sequences/Sequence<T>")) {
            val target = mapValuesTarget(fixture, receiver = receiver)
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedLibraryCallable.admit(target, fixture.base.position(0)),
            )
        }
    }

    @Test
    fun `mapValues source shape cannot admit foreign facade or context receiver`() {
        val fixture = ReviewedPositionFixture()
        val targets =
            listOf(
                mapValuesTarget(fixture, classEntry = "fixture/UserMaps.class"),
                mapValuesTarget(fixture, contexts = listOf("fixture/Context")),
            )
        for (target in targets) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedLibraryCallable.admit(target, fixture.base.position(0)),
            )
        }
    }

    @Test
    fun `shared artifact owner rejects mixed archives before mapValues expansion`() {
        val fixture = ReviewedPositionFixture()
        val selected = fixture.callback.selected
        val forbidden =
            listOf(
                ReviewedPositionFixture.BINARY to ReviewedValueFixture.SOURCE_HASH,
                ReviewedValueFixture.BINARY_HASH to ReviewedPositionFixture.SOURCES,
                "a".repeat(64) to ReviewedPositionFixture.SOURCES,
            )
        for ((binary, sources) in forbidden) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
                ReviewedLibraryArtifact.admit(selected, fixture.base.digest(binary), fixture.base.digest(sources)),
            )
        }
    }

    @Test
    fun `synthetic mapValues declaration admits exact reviewed artifact`() {
        val fixture = ReviewedPositionFixture()
        val admitted = ReviewedLibraryCallable.admit(mapValuesTarget(fixture), fixture.base.position(0))
        assertTrue(admitted is Refinement.Refined, "Missing reviewed mapValues admission: $admitted")
        val callable = (admitted as Refinement.Refined).value
        assertEquals("MAP_VALUES", callable.operation.name)
        val artifact =
            ReviewedLibraryArtifact.admit(
                    callable,
                    fixture.base.digest(ReviewedPositionFixture.BINARY),
                    fixture.base.digest(ReviewedPositionFixture.SOURCES),
                )
                .flowRefined()
        assertEquals("KOTLIN_2_3_0_MAP_VALUES_V1", artifact.rule.name)
        assertEquals("commonMain/kotlin/collections/Maps.kt", artifact.sourceEntry)
        assertEquals("1eed0a8e3180aa82e46bcb0049efcaa80c4b6bdee50aef9754b848ee171cdde7", artifact.sourceEntrySha256)
        assertEquals("520e0f6d39db8a20da2ae0b5098ec91be861892695db7a40fef3480a1a7a80b4", artifact.facadeSha256)
        assertEquals("bb2bb2582743754096256a7cb4cd3a219bfb498a0de1a12f725e7e999227d389", artifact.implementationSha256)
        assertTrue(
            artifact.sourceSections.map { it.firstLine to it.lastLine }.containsAll(listOf(465 to 467, 414 to 416))
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(
                callable,
                fixture.base.digest(ReviewedValueFixture.BINARY_HASH),
                fixture.base.digest(ReviewedValueFixture.SOURCE_HASH),
            ),
        )
    }

    private fun mapValuesTarget(
        fixture: ReviewedPositionFixture,
        receiver: String = "kotlin/collections/Map<out K,V>",
        classEntry: String = "kotlin/collections/MapsKt.class",
        contexts: List<String> = emptyList(),
    ): CompilerGroundedSymbolEvidence =
        fixture.base.target(
            qualified = "kotlin.collections.mapValues",
            receiver = receiver,
            parameters = listOf("(kotlin/collections/Map.Entry<K,V>)->R"),
            typeParameters = 3,
            contextReceivers = contexts,
            file =
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///library.jar!/$classEntry").flowRefined()
                ),
        )
}
