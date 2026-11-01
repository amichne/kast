package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedEagerAdmissionPositiveTest {
    @Test
    fun `synthetic filter admits only exact reviewed artifact`() = admit(ReviewedEagerAdmissionCase.ITERABLE_FILTER)

    @Test
    fun `synthetic mapNotNull admits only exact reviewed artifact`() =
        admit(ReviewedEagerAdmissionCase.ITERABLE_MAP_NOT_NULL)

    @Test
    fun `synthetic groupBy selector overload admits exact reviewed artifact`() =
        admit(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY)

    @Test
    fun `synthetic groupBy transform overload binds both callback formals`() =
        admit(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)

    @Test
    fun `synthetic top level run admits its receiver absent overload`() =
        admit(ReviewedEagerAdmissionCase.RUN_TOP_LEVEL)

    @Test
    fun `synthetic receiver run admits its receiver callback overload`() =
        admit(ReviewedEagerAdmissionCase.RUN_RECEIVER)

    @Test fun `synthetic with binds callback formal one`() = admit(ReviewedEagerAdmissionCase.WITH)

    @Test fun `synthetic apply admits its receiver callback overload`() = admit(ReviewedEagerAdmissionCase.APPLY)

    private fun admit(operation: ReviewedEagerAdmissionCase) {
        val fixture = ReviewedPositionFixture().base
        for (position in operation.callbackPositions) {
            val admitted = ReviewedLibraryCallable.admit(operation.target(fixture), fixture.position(position))
            assertTrue(
                admitted is Refinement.Refined,
                "Missing ${operation.name} formal $position admission: $admitted",
            )
            val callable = (admitted as Refinement.Refined).value
            assertEquals(operation.name, callable.operation.name)
            assertEquals(position, callable.position.value)
            val artifact =
                ReviewedLibraryArtifact.admit(
                        callable,
                        fixture.digest(ReviewedPositionFixture.BINARY),
                        fixture.digest(ReviewedPositionFixture.SOURCES),
                    )
                    .flowRefined()
            assertEquals("KOTLIN_2_3_0_${operation.name}_V1", artifact.rule.name)
            assertEquals(operation.sourceEntry, artifact.sourceEntry)
            assertEquals(operation.sourceHash, artifact.sourceEntrySha256)
            assertEquals(operation.facadeHash, artifact.facadeSha256)
            assertEquals(operation.implementationHash, artifact.implementationSha256)
            assertTrue(operation.sourceSpan in artifact.sourceSections.map { it.firstLine to it.lastLine })
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
                ReviewedLibraryArtifact.admit(
                    callable,
                    fixture.digest(ReviewedValueFixture.BINARY_HASH),
                    fixture.digest(ReviewedValueFixture.SOURCE_HASH),
                ),
            )
        }
    }
}
