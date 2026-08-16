package io.github.amichne.kast.change.apply

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenameSymbolApplyTest {
    private val fixture = ApplyTestFixture()
    private val plan = fixture.renamePlan()

    @Test
    fun `exact rename changes only compiler-grounded occurrences`() {
        val admitted = assertInstanceOf(
            Refinement.Refined::class.java,
            MutationAdmissionService().admit(
                fixture.request(plan = plan),
                fixture.observed(),
            ),
        ).value as AdmittedMutation

        assertEquals(
            "package sample\n\nfun renamedService(): Int = 0\n",
            admitted.write.postimage.text,
        )
        assertTrue(admitted.write.postimage.text.startsWith("package sample\n\nfun "))
        assertTrue(admitted.write.postimage.text.endsWith("(): Int = 0\n"))
    }

    @Test
    fun `stale generation cannot acquire rename mutation authority`() {
        val result = MutationAdmissionService().admit(
            fixture.request(
                plan = plan,
                current = fixture.workspace(generationValue = 12L, sourceState = "state-12"),
            ),
            fixture.observed(),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.STALE_GENERATION, result.failure)
    }
}
