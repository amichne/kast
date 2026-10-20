package fixture.oracle

data class EmittedWitness(
    val seed: Seed,
    val verdict: Verdict,
    val route: FieldRoute,
    val implementation: String? = null,
    val sourceSet: String = "main",
)

data class KnownExclusion(val implementation: String, val policy: SourcePolicy)

enum class QueryCompletion { COMPLETE, COMPLETION_UNPROVEN }

data class RecordedEvidence(
    val witnesses: List<EmittedWitness>,
    val exclusions: Set<KnownExclusion> = emptySet(),
    val relationshipUnproven: Set<String> = emptySet(),
    val completion: QueryCompletion = QueryCompletion.COMPLETION_UNPROVEN,
)

sealed interface RowResult {
    val id: String

    data class PositiveSatisfied(override val id: String, val matchedRouteCount: Int) : RowResult
    data class PositiveMissing(override val id: String, val missingRoutes: List<FieldRoute>) : RowResult
    data class ForbiddenEmitted(override val id: String, val witness: EmittedWitness) : RowResult
    data class ForbiddenNotEmitted(override val id: String, val inspectedWitnessCount: Int) : RowResult
    data class ExclusionSatisfied(override val id: String) : RowResult
    data class ExclusionMissing(override val id: String) : RowResult
    data class OutsidePolicy(override val id: String) : RowResult
}

fun evaluateRow(row: OracleRow, policy: SourcePolicy, evidence: RecordedEvidence): RowResult {
    if (policy !in row.policies) return RowResult.OutsidePolicy(row.id)
    if (row.verdict == Verdict.EXCLUDED) {
        return if (KnownExclusion(checkNotNull(row.implementation), policy) in evidence.exclusions) {
            RowResult.ExclusionSatisfied(row.id)
        } else {
            RowResult.ExclusionMissing(row.id)
        }
    }
    val candidates = evidence.witnesses.filter { witness ->
        witness.seed == row.seed && (row.implementation == null || witness.implementation == row.implementation) &&
            witness.sourceSet == row.sourceSet
    }
    if (row.verdict == Verdict.FORBIDDEN) {
        val forbidden = candidates.firstOrNull { witness -> row.routes.any { matchesForbidden(it, witness.route) } }
        return if (forbidden == null) RowResult.ForbiddenNotEmitted(row.id, candidates.size)
        else RowResult.ForbiddenEmitted(row.id, forbidden)
    }
    val missing = row.routes.filter { route ->
        candidates.none { it.verdict == row.verdict && matches(route, it.route) }
    }
    return if (missing.isEmpty()) RowResult.PositiveSatisfied(row.id, row.routes.size)
    else RowResult.PositiveMissing(row.id, missing)
}

private fun matches(expected: FieldRoute, actual: FieldRoute): Boolean =
    matchesLocation(expected, actual) &&
        actual.transfers.containsAll(expected.transfers) && actual.guards.containsAll(expected.guards)

private fun matchesLocation(expected: FieldRoute, actual: FieldRoute): Boolean =
    matchesFields(expected.seedFields, actual.seedFields) && matchesDestination(expected.destination, actual.destination) &&
        matchesFields(expected.destinationFields, actual.destinationFields)

private fun matchesForbidden(expected: FieldRoute, actual: FieldRoute): Boolean =
    matchesFields(expected.seedFields, actual.seedFields) && matchesDestination(expected.destination, actual.destination) &&
        (expected.destinationFields.isEmpty() || matchesFields(expected.destinationFields, actual.destinationFields))

private fun matchesDestination(expected: String, actual: String): Boolean =
    if (expected.endsWith(":arguments")) {
        actual.substringBefore(':').removeSuffix(".<init>") == expected.substringBefore(':')
    } else expected == actual

private fun matchesFields(expected: List<String>, actual: List<String>): Boolean =
    if (expected.lastOrNull() == "*") actual.take(expected.size - 1) == expected.dropLast(1) else expected == actual
