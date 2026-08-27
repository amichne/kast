package support.delivery

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class Kvp040ReviewAdmissionTest {
    @Test
    fun `complete review covers every authority and stale head rejects`() {
        val head = DeliveryGeneration("a".repeat(40))
        val candidate = Kvp040ReviewDocument(
            schemaVersion = 1,
            taskId = "KVP-040",
            outcome = Kvp040Outcome.COMPLETE,
            repositoryHead = head.value,
            baseHead = "b".repeat(40),
            diffDigest = "c".repeat(64),
            changedFileCount = 1,
            coverage = Kvp040CoverageArea.entries.map { area ->
                Kvp040CoverageDocument(area, "authority/${area.name}", "d".repeat(64))
            },
            findings = emptyList(),
            unresolvedValidFindingCount = 0,
        )

        assertInstanceOf(
            Kvp040ReviewAdmission.Complete::class.java,
            admitKvp040Review(candidate, head),
        )
        assertInstanceOf(
            Kvp040ReviewAdmission.Rejected::class.java,
            admitKvp040Review(candidate, DeliveryGeneration("e".repeat(40))),
        )
        assertInstanceOf(
            Kvp040ReviewAdmission.Rejected::class.java,
            admitKvp040Review(
                candidate.copy(
                    coverage = candidate.coverage.mapIndexed { index, coverage ->
                        if (index == 0) coverage.copy(evidenceDigest = "unsupported") else coverage
                    },
                ),
                head,
            ),
        )
    }
}
