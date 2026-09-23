package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicQueryContract
import io.github.amichne.kast.appserver.query.PublicQueryDocument
import io.github.amichne.kast.appserver.query.PublicQueryDocumentType
import io.github.amichne.kast.appserver.query.PublicQuerySearch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KastQueryInputTest {
    @Test
    fun `retired canonical query route rejects admitted historical syntax`() {
        val name = (ProtocolText.parse("Order") as Refinement.Refined).value
        val raw =
            Json.encodeToJsonElement(
                PublicQueryDocument.serializer(),
                PublicQueryDocument(PublicQueryDocumentType.QUERY, PublicQuerySearch(name)),
            )
        val admitted =
            when (val result = PublicQueryContract.schema.admit(raw)) {
                is Validation.Validated -> result.value
                is Validation.Rejected -> error("Historical query fixture must satisfy its schema")
            }

        val rejection =
            assertInstanceOf(Validation.Rejected::class.java, admitKastInput(CanonicalOperation.QUERY_RUN, admitted))
        assertEquals(KastToolInputFailure.SchemaMismatch, rejection.failures.first())
    }
}
