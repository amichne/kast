package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedEagerAdmissionNegativeTest {
    @Test
    fun `eager names cannot admit another declared receiver overload`() {
        val fixture = ReviewedPositionFixture().base
        for (operation in ReviewedEagerAdmissionCase.entries) {
            val target = operation.target(fixture, receiver = "fixture/OtherReceiver")
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedLibraryCallable.admit(target, fixture.position(operation.callbackPositions.first())),
            )
        }
    }

    @Test
    fun `eager names cannot admit another generic overload`() {
        val fixture = ReviewedPositionFixture().base
        for (operation in ReviewedEagerAdmissionCase.entries) {
            val target = operation.target(fixture, typeParameters = operation.typeParameters + 1)
            // Three type parameters select groupBy's value-transform identity, but its second formal is missing.
            val expectedFailure =
                if (operation == ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY)
                    ReviewedCallbackValueFailure.FORMAL_MISMATCH
                else ReviewedCallbackValueFailure.CALLABLE_MISMATCH
            assertEquals(
                Refinement.Rejected(expectedFailure),
                ReviewedLibraryCallable.admit(target, fixture.position(operation.callbackPositions.first())),
                "Forbidden generic overload for ${operation.name}",
            )
        }
    }

    @Test
    fun `eager source signatures reject foreign facades and context receivers`() {
        val fixture = ReviewedPositionFixture().base
        for (operation in ReviewedEagerAdmissionCase.entries) {
            val targets =
                listOf(
                    operation.target(fixture, file = "fixture/UserEager.class"),
                    operation.target(fixture, contexts = listOf("fixture/Context")),
                )
            for (target in targets) {
                assertEquals(
                    Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                    ReviewedLibraryCallable.admit(target, fixture.position(operation.callbackPositions.first())),
                )
            }
        }
    }

    @Test
    fun `noncallback formal cannot authorize an eager rule`() {
        val fixture = ReviewedPositionFixture().base
        for (operation in ReviewedEagerAdmissionCase.entries) {
            val forbiddenPositions = (0..operation.parameters.size).filter { it !in operation.callbackPositions }
            for (position in forbiddenPositions) {
                val admitted = ReviewedLibraryCallable.admit(operation.target(fixture), fixture.position(position))
                assertTrue(
                    admitted is Refinement.Rejected,
                    "Forbidden ${operation.name} callback formal $position: $admitted",
                )
                // Before family admission the callable gate rejects; after admission the formal gate rejects.
                assertTrue(
                    admitted.reviewedFailure() in
                        setOf(
                            ReviewedCallbackValueFailure.CALLABLE_MISMATCH,
                            ReviewedCallbackValueFailure.FORMAL_MISMATCH,
                        )
                )
            }
        }
    }

    @Test
    fun `existing formal owner rejects wrong parameter before eager rule expansion`() {
        val fixture = ReviewedPositionFixture().base
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH),
            ReviewedLibraryCallable.admit(fixture.selected.target, fixture.position(1)),
        )
    }
}
