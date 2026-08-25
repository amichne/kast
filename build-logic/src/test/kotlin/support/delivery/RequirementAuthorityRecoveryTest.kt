package support.delivery

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequirementAuthorityRecoveryTest {
    @Test
    fun `persisted goal replaces unavailable delivery authority without claiming a digest match`() {
        val program = KastVfsPassiveReusedIndexProgram.definition

        assertEquals(
            PERSISTED_GOAL_DIGEST,
            program.requirementFingerprint.value,
            "the exact persisted goal must own requirement identity",
        )
        assertEquals(
            PERSISTED_GOAL_DIGEST,
            program.sourceDigests.getValue("deliveryAuthority").value,
            "delivery authority must bind the same recovered bytes",
        )
        assertEquals(
            ORIGINAL_DELIVERY_AUTHORITY_DIGEST,
            KastVfsPassiveReusedIndexProgram.SUPERSEDED_REQUIREMENT_FINGERPRINT.value,
            "the unreachable byte identity must remain typed provenance",
        )
        assertTrue(
            KastVfsPassiveReusedIndexProgram.SUPERSEDED_REQUIREMENT_FINGERPRINT !=
                KastVfsPassiveReusedIndexProgram.REQUIREMENT_FINGERPRINT,
            "superseded and active authority identities must remain distinct",
        )
        assertTrue(
            AuthorityContradiction.entries.any {
                it.name == "ORIGINAL_DELIVERY_AUTHORITY_BYTES_UNAVAILABLE"
            },
            "the old pinned digest must remain an explicit contradiction",
        )
        val authorityTask = program.tasks.single { it.id == TaskId("KVP-001") }
        val candidates = KastVfsPassiveReusedIndexProgram.authoritySourceCandidates
        assertEquals(4, candidates.size)
        assertTrue(candidates.all { !Path.of(it.value).isAbsolute })
        assertTrue(candidates.all { it.value in authorityTask.allowedReads })
        assertTrue(authorityTask.allowedReads.none { it.startsWith("/mnt/") })
        assertEquals(
            setOf(
                "build/reports/delivery/KVP-001-authority-ledger.json",
                "build/reports/delivery/KVP-001-contradictions.md",
                "build/reports/delivery/KVP-001-authority.json",
            ),
            authorityTask.allowedWrites.toSet(),
        )
    }

    private companion object {
        const val PERSISTED_GOAL_DIGEST =
            "de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"
        const val ORIGINAL_DELIVERY_AUTHORITY_DIGEST =
            "55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e"
    }
}
