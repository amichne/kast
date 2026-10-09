package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PublicToolCompletionPolicyTest {
    private val source
        get() =
            PublicToolReferenceSource(
                (io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                        listOf(
                            (io.github.amichne.kast.protocol.contract.ProtocolText.parse("exact:v2:opaque")
                                    as Refinement.Refined)
                                .value
                        )
                    ) as Refinement.Refined)
                    .value
            )

    @Test
    fun `public run has no completion input and retains fixed compiler static proof`() {
        val input = encodePublicTool(PublicToolQuerySymbols(PublicToolRunAction(source)), Json)
        assertFalse(input.jsonObject.getValue("request").jsonObject.containsKey("completion"))
        val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input) as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(QueryCompletionDocument(), QueryQuestionDocument.from(run).completion)
    }

    @Test
    fun `public run rejects every supplied completion field before lowering`() {
        for (policy in
            listOf(
                null,
                InvalidPublicCompletionPolicy("PROGRESSIVE"),
                InvalidPublicCompletionPolicy("COMPLETE_ONLY", "COMPILER_RESOLVED_STATIC_V1"),
                InvalidPublicCompletionPolicy("UNKNOWN"),
            )) {
            val input =
                Json.encodeToJsonElement(
                    serializer<ObsoletePublicCompletionEnvelope>(),
                    ObsoletePublicCompletionEnvelope(ObsoletePublicCompletionRun(source, policy)),
                )
            assertEquals(
                Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input),
                policy.toString(),
            )
        }
    }
}

/** Negative fixture deliberately supplies a removed public input field, including explicit null. */
@kotlinx.serialization.Serializable
private data class ObsoletePublicCompletionEnvelope(val request: ObsoletePublicCompletionRun)

@kotlinx.serialization.Serializable
private data class ObsoletePublicCompletionRun(
    val source: PublicToolSource,
    val completion: InvalidPublicCompletionPolicy?,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS) val type: String = "RUN",
)

@kotlinx.serialization.Serializable
private data class InvalidPublicCompletionPolicy(val type: String, val model: String? = null)
