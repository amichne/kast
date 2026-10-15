package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphBoundaryDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphSubjectDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationOrigin
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionRetentionFailure
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class CallbackGraphFailureSchemaTest {
    private val schemas = LiveReadOutputSchemaTest()
    private val location =
        QueryCallbackGraphBoundaryDocument.from(
            ProtocolText.parse("src/CacheManager.kt").refined(),
            SourceRangeDocument.create(ProtocolOffset.parse(2).refined(), ProtocolOffset.parse(3).refined()).refined(),
        )

    @Test
    fun `installed schema admits callback and callable source subjects without retained results`() {
        for (subject in
            listOf(
                QueryCallbackGraphSubjectDocument.Callback(location, location),
                QueryCallbackGraphSubjectDocument.Callable(location),
            )) {
            val document = project(subject)
            schemas.assertAdmits(CanonicalOperation.QUERY_RUN, document)
        }
    }

    private fun project(subject: QueryCallbackGraphSubjectDocument): JsonObject {
        val failure =
            QueryCallbackGraphFailureDocument(
                QueryCallbackGraphCauseDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                QueryCallbackObservationOrigin.RELATION,
                ProtocolOffset.parse(0).refined(),
                ProtocolOffset.parse(0).refined(),
                subject,
            )
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionCauseDocument.CallbackGraphUnproven(failure),
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.COMPLETED,
                QueryCompletionEvidenceDocument.Unavailable(QueryCompletionRetentionFailure.UNAVAILABLE),
            )
        val projected =
            CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(rejection))
                as ProjectedOperationOutcome.Rejected
        return Json.parseToJsonElement(projected.document.value).jsonObject
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
