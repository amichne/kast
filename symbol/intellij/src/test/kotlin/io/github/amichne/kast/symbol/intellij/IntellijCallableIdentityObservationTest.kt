package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijCallableIdentityObservationTest {
    @Test
    fun `native and enum owned identities emit bounded distinct evidence`() {
        val native = RecordingObservation()
        native.callableIdentity(IntellijCallableIdentityStatus.Native)
        assertEquals(listOf(IntellijReadCounter.COMPILER_NATIVE_CALLABLE_IDENTITIES), native.counters)
        assertEquals(emptyList<IntellijReadTermination>(), native.terminations)
        val entry = RecordingObservation()
        entry.callableIdentity(IntellijCallableIdentityStatus.EnumEntryMember)
        assertEquals(listOf(IntellijReadCounter.COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES), entry.counters)
        assertEquals(emptyList<IntellijReadTermination>(), entry.terminations)
    }

    @Test
    fun `every unavailable ownership stage emits its finite cause`() {
        val expected =
            mapOf(
                IntellijCallableIdentityFailure.UNSUPPORTED_CONTAINER to
                    IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED,
                IntellijCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE to
                    IntellijReadTermination.K2_ENUM_OWNER_UNAVAILABLE,
                IntellijCallableIdentityFailure.INITIALIZER_MISMATCH to
                    IntellijReadTermination.K2_ENUM_INITIALIZER_MISMATCH,
                IntellijCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE to
                    IntellijReadTermination.K2_ENUM_IDENTITY_UNAVAILABLE,
                IntellijCallableIdentityFailure.UNNAMED_MEMBER to IntellijReadTermination.K2_UNNAMED_CALLABLE,
            )
        assertEquals(IntellijCallableIdentityFailure.entries.toSet(), expected.keys)
        for ((reason, termination) in expected) {
            val observation = RecordingObservation()
            observation.callableIdentity(IntellijCallableIdentityStatus.Unavailable(reason))
            assertEquals(listOf(IntellijReadCounter.COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE), observation.counters)
            assertEquals(listOf(termination), observation.terminations)
        }
    }
}

private class RecordingObservation : IntellijReadObservation {
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
