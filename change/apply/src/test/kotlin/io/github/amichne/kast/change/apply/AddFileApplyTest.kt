package io.github.amichne.kast.change.apply

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AddFileApplyTest {
    private val fixture = ApplyTestFixture()
    private val plan = fixture.addFilePlan()

    @Test
    fun `exact absent target admits one whole Kotlin file postimage`() {
        val admitted = assertInstanceOf(
            Refinement.Refined::class.java,
            MutationAdmissionService().admit(
                fixture.request(plan = plan),
                fixture.absent(plan),
            ),
        ).value as AdmittedMutation

        assertEquals(plan.writes.entries.single().source, admitted.write.source)
        assertEquals("package sample\n\nclass Added\n", admitted.write.postimage.text)
    }

    @Test
    fun `file appearing before authority rejects stale AddFile state`() {
        val result = MutationAdmissionService().admit(
            fixture.request(plan = plan),
            fixture.existing(plan, "package sample\n\nclass Other\n"),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.SOURCE_PRECONDITION_MISMATCH, result.failure)
    }

    @Test
    fun `stale generation cannot acquire AddFile mutation authority`() {
        val result = MutationAdmissionService().admit(
            fixture.request(
                plan = plan,
                current = fixture.workspace(generationValue = 12L, sourceState = "state-12"),
            ),
            fixture.absent(plan),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.STALE_GENERATION, result.failure)
    }

    @Test
    fun `unrelated source in caller scope cannot enter AddFile authority`() {
        val result = MutationAdmissionService().admit(
            fixture.request(
                plan = plan,
                scope = RequestedMutationWriteScope(
                    fixture.workspace.root,
                    setOf(plan.writes.entries.single().source, fixture.otherFile()),
                ),
            ),
            fixture.absent(plan),
        ) as Refinement.Rejected

        assertEquals(MutationAdmissionFailure.UNPLANNED_WRITE_SET, result.failure)
    }
}
