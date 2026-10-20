package io.github.amichne.kast.relation.contract

/** Observation counters do not identify the compiler relationship or its qualifications. */
@JvmInline
internal value class ValueImplementationIdentity private constructor(val canonical: String) {
    companion object {
        fun from(batch: RelationBatch, coverage: ValueImplementationCoverage): ValueImplementationIdentity =
            ValueImplementationIdentity(
                buildString {
                    field(batch.request.subject.fingerprint.value)
                    field(batch.request.scopeFingerprint.value)
                    field(batch.request.boundary.canonical(batch.request.subject))
                    when (val position = batch.request.position) {
                        RelationReadPosition.Start -> field("START")
                        is RelationReadPosition.Resume -> {
                            field("RESUME")
                            field(position.continuation.fingerprint.value)
                        }
                    }
                    field(batch.facts.size.toString())
                    batch.facts.forEach { field(it.canonicalProjection()) }
                    field(batch.scopeExclusions.size.toString())
                    batch.scopeExclusions.forEach { field(it.canonicalProjection()) }
                    field(batch.omissions.size.toString())
                    batch.omissions.forEach { omission(it) }
                    implementationCoverage(coverage)
                }
            )
    }
}

private fun StringBuilder.omission(value: RelationOmissionEvidence) {
    field(value.provider.name)
    field(value.reason.name)
    when (val measurement = value.measurement) {
        is RelationOmissionMeasurement.ObservedOnPage -> {
            field("OBSERVED_ON_PAGE")
            field(measurement.items.value.toString())
        }
        RelationOmissionMeasurement.UnmeasuredOnPage -> field("UNMEASURED_ON_PAGE")
    }
    field(value.samples.retention.name)
    field(value.samples.locations.size.toString())
    value.samples.locations.forEach { occurrence ->
        field(occurrence.file.stableValue)
        range(occurrence.range)
    }
}

private fun StringBuilder.implementationCoverage(coverage: ValueImplementationCoverage) {
    when (coverage) {
        is ValueImplementationCoverage.Exhausted -> {
            field("EXHAUSTED")
            field(coverage.evidence.exactCount.value.toString())
        }
        is ValueImplementationCoverage.Incomplete -> {
            val evidence = coverage.evidence
            when (evidence) {
                is RelationIncompleteCoverage.Resumable -> {
                    field("RESUMABLE")
                    field(evidence.continuation.fingerprint.value)
                }
                is RelationIncompleteCoverage.TerminalIncomplete -> field("TERMINAL_INCOMPLETE")
            }
            field(evidence.knownMinimum.value.toString())
            field(evidence.limitations.size.toString())
            evidence.limitations.sortedBy { it.ordinal }.forEach { field(it.name) }
        }
    }
}
