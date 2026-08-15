package io.github.amichne.kast.server

import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class VerifiedAddFileRevalidationLifecycleTest {
    @Test
    fun `revalidation admits every finite semantic planner rejection`() {
        val planId = refined(VerifiedAddFilePlanId.refine("af-" + "4".repeat(64)))
        val version = refined(VerifiedAddFilePlanVersion.refine(0L))
        val failures = listOf(
            VerifiedAddFileFailure.TARGET_ALREADY_EXISTS,
            VerifiedAddFileFailure.TARGET_GENERATED,
            VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED,
            VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE,
            VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID,
        )

        failures.forEach { failure ->
            val result = VerifiedAddFileApplyResult.Rejected(
                planId = planId,
                planVersion = version,
                stage = VerifiedAddFilePlanStage.APPROVED,
                progress = VerifiedAddFileProgress.REVALIDATION,
                failure = failure,
            )

            assertInstanceOf<VerifiedAddFileApplyResultAdmission.Admitted>(
                AdmittedVerifiedAddFileApplyResult.admit(result),
                failure.name,
            )
        }
    }

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value
}
