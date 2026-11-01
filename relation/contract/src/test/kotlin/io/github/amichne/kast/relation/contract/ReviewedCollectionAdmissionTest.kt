package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedCollectionAdmissionTest {
    @Test
    fun `reviewed collection names cannot admit another receiver overload`() {
        val fixture = ReviewedValueFixture()
        for (operation in CollectionAdmissionCase.entries) {
            for (receiver in listOf("kotlin/sequences/Sequence<T>", "kotlin/Array<T>")) {
                val selected =
                    fixture.target(
                        qualified = operation.qualified,
                        receiver = receiver,
                        parameters = listOf(operation.parameter),
                        typeParameters = operation.typeParameters,
                    )
                assertEquals(
                    Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                    ReviewedLibraryCallable.admit(selected, fixture.position(0)),
                )
            }
        }
    }

    @Test
    fun `reviewed collection names cannot admit a wrong facade or context receiver`() {
        val fixture = ReviewedValueFixture()
        val foreign =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///library.jar!/fixture/UserCollections.class").flowRefined()
            )
        for (operation in CollectionAdmissionCase.entries) {
            val targets =
                listOf(
                    fixture.target(
                        qualified = operation.qualified,
                        parameters = listOf(operation.parameter),
                        typeParameters = operation.typeParameters,
                        file = foreign,
                    ),
                    fixture.target(
                        qualified = operation.qualified,
                        parameters = listOf(operation.parameter),
                        typeParameters = operation.typeParameters,
                        contextReceivers = listOf("fixture/Context"),
                    ),
                )
            for (target in targets) {
                assertEquals(
                    Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                    ReviewedLibraryCallable.admit(target, fixture.position(0)),
                )
            }
        }
    }

    @Test
    fun `artifact admission rejects mixed versions and changed bytes before rule expansion`() {
        val fixture = ReviewedValueFixture()
        val forbidden =
            listOf(
                BINARY_230 to ReviewedValueFixture.SOURCE_HASH,
                ReviewedValueFixture.BINARY_HASH to SOURCE_230,
                "a".repeat(64) to SOURCE_230,
                BINARY_230 to "b".repeat(64),
            )
        for ((binary, sources) in forbidden) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
                ReviewedLibraryArtifact.admit(fixture.selected, fixture.digest(binary), fixture.digest(sources)),
            )
        }
    }

    @Test
    fun `exact sortedBy selected signature admits only its reviewed two three artifact`() {
        assertAdmission(CollectionAdmissionCase.SORTED_BY)
    }

    @Test
    fun `exact associate selected signature admits only its reviewed two three artifact`() {
        assertAdmission(CollectionAdmissionCase.ASSOCIATE)
    }

    private fun assertAdmission(operation: CollectionAdmissionCase) {
        val fixture = ReviewedValueFixture()
        val target =
            fixture.target(
                qualified = operation.qualified,
                parameters = listOf(operation.parameter),
                typeParameters = operation.typeParameters,
            )
        assertEquals(operation.identity, target.compilerIdentity.value)
        val admitted = ReviewedLibraryCallable.admit(target, fixture.position(0))
        assertTrue(admitted is Refinement.Refined, "Missing reviewed callable admission: $admitted")
        val callable = (admitted as Refinement.Refined).value
        assertEquals(operation.operation, callable.operation.name)
        val artifact =
            ReviewedLibraryArtifact.admit(callable, fixture.digest(BINARY_230), fixture.digest(SOURCE_230))
                .flowRefined()
        assertEquals(operation.rule, artifact.rule.name)
        assertEquals("commonMain/generated/_Collections.kt", artifact.sourceEntry)
        assertEquals("03b2c7e97271674123941c6d1df5f95a8511d7b1d543fd41d4523af6c15e2970", artifact.sourceEntrySha256)
        assertEquals("516c679f0309fb8a7058dfc33da4f11a4d0b5eab6de8f2225ff15a9ff3e88770", artifact.facadeSha256)
        assertEquals("fb24111a87f4e45b9a082ea1666dd33eb04d7ce979b24fde34fcb6d229371e39", artifact.implementationSha256)
        assertTrue(artifact.sourceSections.map { it.firstLine to it.lastLine }.containsAll(operation.sections))
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(
                callable,
                fixture.digest(ReviewedValueFixture.BINARY_HASH),
                fixture.digest(ReviewedValueFixture.SOURCE_HASH),
            ),
        )
    }

    private companion object {
        const val BINARY_230 = "887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"
        const val SOURCE_230 = "326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"
    }
}

private enum class CollectionAdmissionCase(
    val qualified: String,
    val parameter: String,
    val typeParameters: Int,
    val identity: String,
    val sections: List<Pair<Int, Int>>,
) {
    SORTED_BY(
        "kotlin.collections.sortedBy",
        "(T)->R?",
        2,
        "canonical-signature-sha256-v1|bf6a3011d176a1cee55176b9ccc6e1c5a54803e34efd3dec4d9d54ebaf181b2c",
        listOf(1067 to 1069, 1097 to 1104),
    ),
    ASSOCIATE(
        "kotlin.collections.associate",
        "(T)->kotlin/Pair<K,V>",
        3,
        "canonical-signature-sha256-v1|7c60edb52ce4bce7a112c5452f44e684188a7cb05702fce84d36e32ecd078e4a",
        listOf(1204 to 1207, 1281 to 1286),
    );

    val operation: String
        get() = "ITERABLE_$name"

    val rule: String
        get() = "KOTLIN_2_3_0_ITERABLE_${name}_V1"
}
