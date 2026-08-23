package io.github.amichne.kast.change.apply

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class MutationAdmissionTest {
    private val fixture = ApplyTestFixture()
    private val admission = MutationAdmissionService()

    @Test
    fun `exact current state and singleton write scope are admitted`() {
        assertInstanceOf(
            Refinement.Refined::class.java,
            admission.admit(fixture.request(), fixture.observed()),
        )
    }

    @Test
    fun `classlike add declaration derives a postimage inside the class body`() {
        val fixture = ApplyTestFixture(classLike = true)
        val admitted = admission.admit(fixture.request(), fixture.observed())
            as Refinement.Refined<AdmittedMutation>

        assertEquals(
            "package sample\n\nclass service {\n\n    fun added(): Int = 1\n}\n",
            admitted.value.write.postimage.text,
        )
    }

    @Test
    fun `wrong root stale content and scope remain distinct closed failures`() {
        assertRejected(
            MutationAdmissionFailure.WRONG_ROOT,
            fixture.request(current = fixture.workspace(rawRoot = "/other")),
            fixture.observed(),
        )
        assertRejected(
            MutationAdmissionFailure.STALE_GENERATION,
            fixture.request(current = fixture.workspace(generationValue = 12L, sourceState = "state-12")),
            fixture.observed(),
        )
        assertRejected(
            MutationAdmissionFailure.SOURCE_CONTENT_CHANGED,
            fixture.request(),
            fixture.observed(text = "package sample\n\nfun changed(): Int = 0\n"),
        )
        assertRejected(
            MutationAdmissionFailure.OUT_OF_SCOPE,
            fixture.request(
                scope = RequestedMutationWriteScope(fixture.workspace.root, emptySet()),
            ),
            fixture.observed(),
        )
    }

    @Test
    fun `generated wrong owner and additional writes cannot gain authority`() {
        assertRejected(
            MutationAdmissionFailure.GENERATED_TARGET,
            fixture.request(
                current = fixture.workspace(provenance = SourceRootProvenance.Generated),
            ),
            fixture.observed(),
        )
        assertRejected(
            MutationAdmissionFailure.WRONG_SOURCE_ROOT_OWNER,
            fixture.request(current = fixture.workspace(projectPath = ":other")),
            fixture.observed(),
        )
        assertRejected(
            MutationAdmissionFailure.UNPLANNED_WRITE_SET,
            fixture.request(
                scope = RequestedMutationWriteScope(
                    fixture.workspace.root,
                    setOf(fixture.plan.target.file, fixture.otherFile()),
                ),
            ),
            fixture.observed(),
        )
    }

    private fun assertRejected(
        expected: MutationAdmissionFailure,
        request: AddDeclarationApplyRequest,
        observed: ObservedMutationSource,
    ) {
        val rejected = admission.admit(request, observed) as Refinement.Rejected
        assertEquals(expected, rejected.failure)
    }
}
