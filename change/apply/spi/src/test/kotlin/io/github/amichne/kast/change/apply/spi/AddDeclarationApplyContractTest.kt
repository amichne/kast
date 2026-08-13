package io.github.amichne.kast.change.apply.spi

import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationChangedDocumentPath
import io.github.amichne.kast.change.contract.AddDeclarationChangedDocumentPathFailure
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AddDeclarationApplyContractTest {
    @Test
    fun `changed document path refines only canonical absolute identity`() {
        val path = assertInstanceOf<Refinement.Refined<AddDeclarationChangedDocumentPath>>(
            AddDeclarationChangedDocumentPath.parse("/workspace/kast/Target.kt"),
        ).value

        assertEquals("/workspace/kast/Target.kt", path.value)
        assertInstanceOf<Refinement.Rejected<AddDeclarationChangedDocumentPathFailure>>(
            AddDeclarationChangedDocumentPath.parse("/workspace/kast/../Target.kt"),
        )
    }

    @Test
    fun `closed outcomes make physical mutation progress unambiguous`() {
        val before = AddDeclarationApplyResult.RejectedBeforeMutation(
            AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH,
        )
        val uncertain = AddDeclarationApplyResult.MutationOutcomeUnknown(
            AddDeclarationApplyUncertainFailure.WRITE_COMMAND_FAILED,
        )
        val after = AddDeclarationApplyResult.RecoveryRequiredAfterMutation(
            AddDeclarationApplyRecoveryFailure.DOCUMENT_SAVE_INCOMPLETE,
        )

        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, before.mutationProgress)
        assertEquals(AddDeclarationMutationProgress.MAY_HAVE_BEGUN, uncertain.mutationProgress)
        assertEquals(AddDeclarationMutationProgress.BEGUN, after.mutationProgress)
    }
}
