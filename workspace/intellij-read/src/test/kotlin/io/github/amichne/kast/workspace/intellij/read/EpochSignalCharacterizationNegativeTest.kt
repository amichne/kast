package io.github.amichne.kast.workspace.intellij.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpochSignalCharacterizationNegativeTest {
    @Test
    fun `every missing duplicate or reordered signal authority is rejected`() {
        val document = EpochSignalLedgerContract.document
        document.signals.indices.forEach { index ->
            assertRejected(
                document.copy(signals = document.signals.filterIndexed { at, _ -> at != index }),
                EpochLedgerFailure.SIGNAL_SET_MISMATCH,
            )
        }
        assertRejected(
            document.copy(signals = document.signals + document.signals.first()),
            EpochLedgerFailure.SIGNAL_SET_MISMATCH,
        )
        assertRejected(
            document.copy(signals = document.signals.reversed()),
            EpochLedgerFailure.SIGNAL_SET_MISMATCH,
        )
    }

    @Test
    fun `constant-zero VFS manager counters cannot replace the public event topic`() {
        val document = EpochSignalLedgerContract.document
        document.rejectedConstantZeroAuthorities.indices.forEach { index ->
            assertRejected(
                document.copy(
                    rejectedConstantZeroAuthorities =
                        document.rejectedConstantZeroAuthorities.filterIndexed { at, _ ->
                            at != index
                        },
                ),
                EpochLedgerFailure.CONSTANT_ZERO_AUTHORITY_NOT_REJECTED,
            )
        }
    }

    @Test
    fun `every missing or weakened two-sample characterization case is rejected`() {
        val document = EpochSignalLedgerContract.document
        document.cases.indices.forEach { index ->
            assertRejected(
                document.copy(cases = document.cases.filterIndexed { at, _ -> at != index }),
                EpochLedgerFailure.CASE_SET_MISMATCH,
            )
        }
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.PSI_MOVEMENT) {
                copy(sampleCount = 1)
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.VFS_EVENT_STORM) {
                copy(vfsEventCount = 999)
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.SMART_DUMB_SMART) {
                copy(dumbModeTransitions = emptyList())
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.GRADLE_ROOT_MOVEMENT) {
                copy(projectModelTransitions = emptyList())
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.GRADLE_IMPORT_COMPLETED) {
                copy(
                    projectModelTransitions =
                        listOf(EpochProjectModelTransition.GRADLE_IMPORT_STARTED),
                )
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.COMBINED_MOVEMENT) {
                copy(movedSignals = listOf(EpochSignalCategory.VFS))
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
        assertRejected(
            document.copy(cases = document.cases.update(EpochCaseId.STABLE) {
                copy(observedRelation = EpochSampleRelation.CHANGED)
            }),
            EpochLedgerFailure.CASE_SET_MISMATCH,
        )
    }

    @Test
    fun `every forbidden-work counter fails closed`() {
        val document = EpochSignalLedgerContract.document
        listOf(
            document.copy(vfsRefreshCount = 1),
            document.copy(gradleImportCount = 1),
            document.copy(repositoryWalkCount = 1),
            document.copy(sourceHashCount = 1),
            document.copy(semanticJobCount = 1),
            document.copy(edtWorkCount = 1),
            document.copy(blockingWaitCount = 1),
        ).forEach { mutated ->
            assertRejected(mutated, EpochLedgerFailure.FORBIDDEN_EFFECT_OBSERVED)
        }
    }

    @Test
    fun `malformed open and noncanonical JSON are closed rejections`() {
        assertEquals(
            EpochLedgerAdmission.Rejected(EpochLedgerFailure.MALFORMED_DOCUMENT),
            EpochSignalLedgerContract.admit("{"),
        )
        assertEquals(
            EpochLedgerAdmission.Rejected(EpochLedgerFailure.MALFORMED_DOCUMENT),
            EpochSignalLedgerContract.admit(
                EpochSignalLedgerContract.canonicalBytes.replaceFirst("{", "{\"unknown\":true,"),
            ),
        )
        assertEquals(
            EpochLedgerAdmission.Rejected(EpochLedgerFailure.NON_CANONICAL_DOCUMENT),
            EpochSignalLedgerContract.admit(EpochSignalLedgerContract.canonicalBytes.trim()),
        )
    }

    private fun assertRejected(
        document: IdeEpochSignalLedgerDocument,
        expected: EpochLedgerFailure,
    ) {
        assertEquals(
            EpochLedgerAdmission.Rejected(expected),
            EpochSignalLedgerContract.admit(EpochSignalLedgerContract.encode(document)),
        )
    }

    private fun List<EpochCaseDocument>.update(
        id: EpochCaseId,
        change: EpochCaseDocument.() -> EpochCaseDocument,
    ): List<EpochCaseDocument> = map { case -> if (case.caseId == id) case.change() else case }
}
