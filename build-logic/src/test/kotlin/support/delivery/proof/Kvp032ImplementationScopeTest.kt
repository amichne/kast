package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Kvp032ImplementationScopeTest {
    private val ownRoot = "build-logic/src/main/kotlin/support/delivery"
    private val successorRoot = "$ownRoot/gradle"
    private val ownership = AdmittedKvp032WriteOwnership(
        declaredWrites = listOf(ownRoot),
        ownedWrites = listOf(ownRoot),
        companionWrites = emptyList(),
        successorWrites = listOf(successorRoot),
    )

    @Test
    fun `canonical graph derives only strictly nested later task scopes`() {
        val packet = canonicalKvp032Packet().first
        val admitted = assertInstanceOf(
            Kvp032WriteOwnershipAdmission.Complete::class.java,
            admitKvp032WriteOwnership(packet, KastVfsPassiveReusedIndexProgram.validated),
        ).ownership

        assertTrue(admitted.successorWrites.contains(successorRoot))
        assertFalse(admitted.successorWrites.contains(ownRoot))
    }

    @Test
    fun `later specific owner excludes its mixed checkpoint from KVP 032`() {
        val own = Kvp032ObservedCommit(
            DeliveryGeneration("1".repeat(40)),
            listOf("$ownRoot/tasks/receiptboundary/atomic/kvp032/Kvp032ProofBoundaries.kt"),
        )
        val successor = Kvp032ObservedCommit(
            DeliveryGeneration("2".repeat(40)),
            listOf("$successorRoot/kvp038/Kvp038Boundaries.kt", "scripts/verify_bundle.sh"),
        )

        val admitted = assertInstanceOf(
            Kvp032ImplementationScopeAdmission.Complete::class.java,
            admitKvp032ObservedImplementationScope(listOf(own, successor), ownership),
        )

        assertEquals(listOf(own.revision), admitted.scope.commits.map { it.revision })
    }

    @Test
    fun `unowned mixed checkpoint without successor anchor remains rejected`() {
        val mixed = Kvp032ObservedCommit(
            DeliveryGeneration("3".repeat(40)),
            listOf(
                "$ownRoot/tasks/receiptboundary/atomic/kvp032/Kvp032ProofBoundaries.kt",
                "scripts/unowned-proof-change.sh",
            ),
        )

        assertEquals(
            Kvp032ImplementationScopeAdmission.Rejected(
                Kvp032BoundaryFailure.WRITE_OUTSIDE_DECLARED_SCOPE,
            ),
            admitKvp032ObservedImplementationScope(listOf(mixed), ownership),
        )
    }
}
