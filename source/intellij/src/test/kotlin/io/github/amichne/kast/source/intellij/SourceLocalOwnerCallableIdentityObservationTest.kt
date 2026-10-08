package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceLocalOwnerCallableIdentityObservationTest {
    @Test
    fun `compiler admitted enum owner retains identity and bounded success evidence`() {
        val observation = RecordingLocalOwnerObservation()
        val result =
            SourceLocalOwnerCallableIdentity.EnumEntryMember(
                    CallableId(FqName("supplemental"), FqName("EntryLocalOwner"), Name.identifier("FIRST")),
                    Name.identifier("compute"),
                )
                .observedLocalOwnerIdentity(observation)
        assertEquals(Refinement.Refined(FqName("supplemental.EntryLocalOwner.FIRST.compute")), result)
        assertEquals(listOf(IntellijReadCounter.COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES), observation.counters)
        assertEquals(emptyList<IntellijReadTermination>(), observation.terminations)
    }

    @Test
    fun `native local owner preserves its admitted qualified identity`() {
        val observation = RecordingLocalOwnerObservation()
        val identity = FqName("supplemental.Owner.compute")
        assertEquals(
            Refinement.Refined(identity),
            SourceLocalOwnerCallableIdentity.Native(identity).observedLocalOwnerIdentity(observation),
        )
        assertEquals(listOf(IntellijReadCounter.COMPILER_NATIVE_CALLABLE_IDENTITIES), observation.counters)
        assertEquals(emptyList<IntellijReadTermination>(), observation.terminations)
    }

    @Test
    fun `unavailable local owner retains every finite compiler rejection stage`() {
        val expected =
            mapOf(
                SourceLocalOwnerCallableIdentityFailure.UNSUPPORTED_CONTAINER to
                    IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED,
                SourceLocalOwnerCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE to
                    IntellijReadTermination.K2_ENUM_OWNER_UNAVAILABLE,
                SourceLocalOwnerCallableIdentityFailure.INITIALIZER_MISMATCH to
                    IntellijReadTermination.K2_ENUM_INITIALIZER_MISMATCH,
                SourceLocalOwnerCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE to
                    IntellijReadTermination.K2_ENUM_IDENTITY_UNAVAILABLE,
                SourceLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER to IntellijReadTermination.K2_UNNAMED_CALLABLE,
            )
        assertEquals(SourceLocalOwnerCallableIdentityFailure.entries.toSet(), expected.keys)
        for ((reason, termination) in expected) {
            val observation = RecordingLocalOwnerObservation()
            assertEquals(
                Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable),
                SourceLocalOwnerCallableIdentity.Unavailable(reason).observedLocalOwnerIdentity(observation),
            )
            assertEquals(listOf(IntellijReadCounter.COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE), observation.counters)
            assertEquals(listOf(termination), observation.terminations)
        }
    }
}

private class RecordingLocalOwnerObservation : IntellijReadObservation {
    val counters = mutableListOf<IntellijReadCounter>()
    val terminations = mutableListOf<IntellijReadTermination>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        assertEquals(1, amount)
        counters += counter
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        terminations += reason
    }
}
