package io.github.amichne.kast.change.apply

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplaceDeclarationApplyTest {
    private val fixture = ApplyTestFixture()
    private val plan = fixture.replaceDeclarationPlan()

    @Test
    fun `exact declaration replacement preserves unrelated source`() {
        val admitted = assertInstanceOf(
            Refinement.Refined::class.java,
            MutationAdmissionService().admit(
                fixture.request(plan = plan),
                fixture.observed(),
            ),
        ).value as AdmittedMutation

        assertEquals(
            "package sample\n\nfun service(): Int = 1\n",
            admitted.write.postimage.text,
        )
        assertTrue(admitted.write.postimage.text.startsWith("package sample\n\n"))
        assertTrue(admitted.write.postimage.text.endsWith("\n"))
    }

    @Test
    fun `stale generation cannot acquire declaration replacement authority`() {
        val result = MutationAdmissionService().admit(
            fixture.request(
                plan = plan,
                current = fixture.workspace(generationValue = 12L, sourceState = "state-12"),
            ),
            fixture.observed(),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.STALE_GENERATION, result.failure)
    }

    @Test
    fun `changed declaration preimage rejects exact replacement derivation`() {
        val result = DerivedMutationPostimage.derive(
            fixture.existing(plan, "package sample\n\nfun service(): Int = 9\n"),
            plan.writes.entries.single().mutations,
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.MUTATION_PREIMAGE_MISMATCH, result.failure)
    }

    @Test
    fun `unrelated source cannot enter declaration replacement authority`() {
        val result = MutationAdmissionService().admit(
            fixture.request(
                plan = plan,
                scope = RequestedMutationWriteScope(
                    fixture.workspace.root,
                    setOf(plan.writes.entries.single().source, fixture.otherFile()),
                ),
            ),
            fixture.observed(),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.UNPLANNED_WRITE_SET, result.failure)
    }
}
