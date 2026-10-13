package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement

internal object HistoricalSnapshotAdmission {
    fun admit(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> {
        when (val structural = structuralAdmission(input)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return structural
        }
        when (val membership = membershipAdmission(input)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return membership
        }
        when (val endpoints = endpointAdmission(input)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return endpoints
        }
        return coverageAdmission(input)
    }

    private fun membershipAdmission(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> {
        val modules = input.modules.associateBy { it.id }
        val files = input.files.associateBy { it.path }
        if (input.dependencies.any { it.source !in modules || it.target !in modules }) {
            return Refinement.Rejected(HistoricalAdmissionFailure.UNKNOWN_MODULE)
        }
        for (file in input.files) when (val admitted = HistoricalFactPolicy.admitFile(file, modules)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        if (input.declarations.any { it.id.file !in files } || input.occurrences.any { it.sourceFile !in files }) {
            return Refinement.Rejected(HistoricalAdmissionFailure.UNKNOWN_FILE)
        }
        return Refinement.Refined(Unit)
    }

    private fun endpointAdmission(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> {
        val declarations = input.declarations.mapTo(hashSetOf()) { it.id }
        for (occurrence in input.occurrences) when (
            val admitted = HistoricalFactPolicy.admitOccurrence(occurrence, declarations)
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        return Refinement.Refined(Unit)
    }

    private fun structuralAdmission(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> =
        when {
            input.modules.map { it.id }.distinct().size != input.modules.size ->
                Refinement.Rejected(HistoricalAdmissionFailure.DUPLICATE_MODULE)
            input.files.map { it.path }.distinct().size != input.files.size ->
                Refinement.Rejected(HistoricalAdmissionFailure.DUPLICATE_FILE)
            input.declarations.map { it.id }.distinct().size != input.declarations.size ->
                Refinement.Rejected(HistoricalAdmissionFailure.DUPLICATE_DECLARATION)
            input.occurrences.distinct().size != input.occurrences.size ->
                Refinement.Rejected(HistoricalAdmissionFailure.DUPLICATE_OCCURRENCE)
            input.dependencies.distinct().size != input.dependencies.size ->
                Refinement.Rejected(HistoricalAdmissionFailure.DUPLICATE_DEPENDENCY)
            else -> Refinement.Refined(Unit)
        }

    private fun coverageAdmission(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> {
        val expected = input.modules.flatMap { module -> HistoricalFactFamily.entries.map { module.id to it } }.toSet()
        val actual = input.coverage.map { it.module to it.family }
        if (actual.toSet() != expected || actual.distinct().size != actual.size)
            return Refinement.Rejected(HistoricalAdmissionFailure.COVERAGE_DOMAIN)
        if (
            input.coverage.any {
                it.family in setOf(HistoricalFactFamily.MODULES, HistoricalFactFamily.FILES) &&
                    it.status != HistoricalCoverageStatus.Complete
            }
        ) {
            return Refinement.Rejected(HistoricalAdmissionFailure.INVENTORY_INCOMPLETE)
        }
        return profileAdmission(input)
    }

    private fun profileAdmission(input: HistoricalSnapshotInput): Refinement<Unit, HistoricalAdmissionFailure> =
        when (input.header.profile) {
            HistoricalProfile.STRUCTURE_V1 ->
                if (
                    input.occurrences.isNotEmpty() ||
                        input.coverage.any {
                            it.family == HistoricalFactFamily.REFERENCES &&
                                it.status != HistoricalCoverageStatus.NotCollected
                        }
                )
                    Refinement.Rejected(HistoricalAdmissionFailure.PROFILE_MISMATCH)
                else Refinement.Refined(Unit)
            HistoricalProfile.REFERENCES_V1 ->
                if (
                    input.coverage.any {
                        it.family == HistoricalFactFamily.REFERENCES &&
                            it.status == HistoricalCoverageStatus.NotCollected
                    }
                )
                    Refinement.Rejected(HistoricalAdmissionFailure.PROFILE_MISMATCH)
                else Refinement.Refined(Unit)
        }
}
