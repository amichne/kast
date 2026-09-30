package io.github.amichne.kast.relation.contract

/** Whether every distinct located sample observed so far remains in the bounded collection. */
enum class RelationOmissionSampleRetention {
    COMPLETE,
    TRUNCATED,
}

/** Only observation can establish truncation; no missing-population count is inferred. */
class RelationOmissionSamples
private constructor(
    val locations: List<RelationOccurrence>,
    val retention: RelationOmissionSampleRetention,
) {
    fun observe(location: RelationOccurrence): RelationOmissionSamples =
        when {
            retention == RelationOmissionSampleRetention.TRUNCATED || location in locations -> this
            locations.size < MAXIMUM_SAMPLES ->
                RelationOmissionSamples(
                    java.util.Collections.unmodifiableList(locations + location),
                    RelationOmissionSampleRetention.COMPLETE,
                )
            else -> RelationOmissionSamples(locations, RelationOmissionSampleRetention.TRUNCATED)
        }

    override fun equals(other: Any?): Boolean =
        other is RelationOmissionSamples && locations == other.locations && retention == other.retention

    override fun hashCode(): Int = 31 * locations.hashCode() + retention.hashCode()

    override fun toString(): String = "RelationOmissionSamples(retention=$retention, locations=$locations)"

    companion object {
        const val MAXIMUM_SAMPLES = 3

        val Empty = RelationOmissionSamples(emptyList(), RelationOmissionSampleRetention.COMPLETE)

        fun observed(locations: Iterable<RelationOccurrence>): RelationOmissionSamples =
            locations.fold(Empty) { retained, location -> retained.observe(location) }
    }
}
