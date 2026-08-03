package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.RawExactFileObservationPath
import io.github.amichne.kast.api.contract.MutationAttemptId
import io.github.amichne.kast.api.contract.query.RawExactFileObservationQuery
import io.github.amichne.kast.api.protocol.ValidationException

data class ParsedRawExactFileObservationQuery(
    val filePath: RawExactFileObservationPath,
    val mutationAttemptId: MutationAttemptId? = null,
)

fun RawExactFileObservationQuery.parsed(): ParsedRawExactFileObservationQuery =
    rawExactFileObservationValidationBoundary {
        ParsedRawExactFileObservationQuery(
            filePath = RawExactFileObservationPath.parse(filePath),
            mutationAttemptId = mutationAttemptId?.let(MutationAttemptId::parse),
        )
    }

private inline fun <T> rawExactFileObservationValidationBoundary(block: () -> T): T = try {
    block()
} catch (failure: ValidationException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ValidationException(failure.message ?: "Invalid raw exact-file observation request")
}
