package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.ReferenceAuthorityEpoch
import io.github.amichne.kast.protocol.contract.ReferenceAuthorityMismatch
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class AuthorityMismatchOutputSchemaTest {
    private val schema = LiveReadOutputSchemaTest()

    @Test
    fun `source query and concat authority failures satisfy installed output schemas`() =
        with(schema) {
            val epoch =
                parsed(
                    ReferenceAuthorityMismatch.EpochMismatch.compare(
                        parsed(ReferenceAuthorityEpoch.parse(3)),
                        parsed(ReferenceAuthorityEpoch.parse(4)),
                    )
                )
            for (mismatch in listOf(epoch, ReferenceAuthorityMismatch.ContentViewMismatch)) {
                val reference =
                    QueryRunRejection.ReferenceRejected.staleAuthority(parsed(ProtocolOffset.parse(0)), mismatch)
                val step =
                    QueryRunRejection.StepReferenceRejected.fromReference(parsed(ProtocolOffset.parse(1)), reference)
                for (failure in listOf(reference, step)) {
                    val document = CanonicalQueryCliDocuments.project(OperationOutcome.Rejected(failure)).document()
                    assertAdmits(CanonicalOperation.QUERY_RUN, document)
                    val rejection = document.getValue("rejection").jsonObject
                    val unknown =
                        rejection.getValue("authority_mismatch").jsonObject.with("type", JsonPrimitive("UNKNOWN"))
                    assertRejects(
                        CanonicalOperation.QUERY_RUN,
                        document.with("rejection", rejection.with("authority_mismatch", unknown)),
                    )
                }
                val source =
                    SourceReadFailureDetail.ReferenceRejected.staleAuthority(SourceReferenceRole.CANDIDATE, mismatch)
                val document = Json.parseToJsonElement(canonicalReadRejectedDocument(source).value).jsonObject
                assertAdmits(CanonicalOperation.SOURCE_READ, document)
                val rejection = document.getValue("reason").jsonObject
                val unknown = rejection.getValue("authority_mismatch").jsonObject.with("type", JsonPrimitive("UNKNOWN"))
                assertRejects(
                    CanonicalOperation.SOURCE_READ,
                    document.with("reason", rejection.with("authority_mismatch", unknown)),
                )
            }
        }

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit fixture: ${refinement.failure}")
        }
}
