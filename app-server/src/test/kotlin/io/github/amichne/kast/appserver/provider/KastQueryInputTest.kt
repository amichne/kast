package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicQueryContract
import io.github.amichne.kast.appserver.query.PublicQueryDocument
import io.github.amichne.kast.appserver.query.PublicQueryDocumentType
import io.github.amichne.kast.appserver.query.PublicQuerySearch
import io.github.amichne.kast.appserver.query.PublicToolCheckDiagnostics
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.registry.AgentToolInputBinding
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
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

    @Test
    fun `diagnostics require the bound public tool presentation`() {
        val path = (ProtocolText.parse("src/Main.kt") as Refinement.Refined).value
        val raw =
            Json.encodeToJsonElement(
                PublicToolCheckDiagnostics.serializer(),
                PublicToolCheckDiagnostics(path, null),
            )
        val admitted =
            when (val result = PublicToolContract.schema(PublicToolIdentity.CHECK_DIAGNOSTICS).admit(raw)) {
                is Validation.Validated -> result.value
                is Validation.Rejected -> error("Diagnostics fixture must satisfy its schema")
            }

        val canonical =
            assertInstanceOf(
                Validation.Rejected::class.java,
                admitKastInput(CanonicalOperation.DIAGNOSTIC_CHECK, admitted),
            )
        assertEquals(KastToolInputFailure.SchemaMismatch, canonical.failures.first())
        val facade =
            admitKastInput(
                CanonicalOperation.DIAGNOSTIC_CHECK,
                admitted,
                AgentToolInputBinding.Facade(PublicToolIdentity.CHECK_DIAGNOSTICS),
            )
        assertInstanceOf(Validation.Validated::class.java, facade)
    }
}
