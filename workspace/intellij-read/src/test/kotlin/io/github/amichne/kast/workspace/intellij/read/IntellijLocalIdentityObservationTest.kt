package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijLocalIdentityObservationTest {
    @Test
    fun `admitted local identity records only positive proof count`() {
        val observation = Observation()
        observation.localIdentityAdmitted()
        assertEquals(listOf(IntellijReadCounter.LOCAL_DECLARATION_IDENTITIES_ADMITTED), observation.counts)
        assertEquals(emptyList<IntellijReadTermination>(), observation.terminations)
    }

    @Test
    fun `every rejected local proof retains its finite outcome and work exhaustion termination`() {
        val cases =
            listOf(
                LocalDeclarationProjectionFailure.WorkLimitReached to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT,
                LocalDeclarationProjectionFailure.OwnerDepthExceeded to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT,
                LocalDeclarationProjectionFailure.CompilerTypeError to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_TYPE_ERROR,
                LocalDeclarationProjectionFailure.CompilerTypeUnsupported to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_TYPE_UNSUPPORTED,
                LocalDeclarationProjectionFailure.UnsupportedDeclaration to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_KIND_UNSUPPORTED,
                LocalDeclarationProjectionFailure.SignatureUnavailable to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_SIGNATURE_UNAVAILABLE,
                LocalDeclarationProjectionFailure.SourceUnavailable to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_SOURCE_UNAVAILABLE,
                LocalDeclarationProjectionFailure.OwnerUnavailable to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_OWNER_UNAVAILABLE,
                LocalDeclarationProjectionFailure.CompilerOwnerUnavailable to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_COMPILER_OWNER_UNAVAILABLE,
                LocalDeclarationProjectionFailure.LexicalAncestryUnavailable to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_LEXICAL_OWNER_UNAVAILABLE,
                LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_FILE) to
                    IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_ADDRESS_INVALID,
            )
        for ((failure, counter) in cases) {
            val observation = Observation()
            observation.localIdentityRejected(failure)
            assertEquals(listOf(IntellijReadCounter.LOCAL_DECLARATION_IDENTITIES_REJECTED, counter), observation.counts)
            assertEquals(
                if (counter == IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT)
                    listOf(IntellijReadTermination.WORK_LIMIT)
                else emptyList(),
                observation.terminations,
            )
        }
    }

    private class Observation : IntellijReadObservation {
        val counts = mutableListOf<IntellijReadCounter>()
        val terminations = mutableListOf<IntellijReadTermination>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(1, amount)
            assertEquals(IntellijReadContributor.NONE, contributor)
            counts.add(counter)
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            assertEquals(IntellijReadContributor.NONE, contributor)
            terminations.add(reason)
        }
    }
}
