package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun input(policy: InvalidPublicCompletionPolicy) =
        Json.encodeToJsonElement(
            InvalidPublicCompletionEnvelope.serializer(),
            InvalidPublicCompletionEnvelope(InvalidPublicCompletionRun(source, policy)),
        )

    @Test
    fun `actual public run lowers explicit complete only model`() {
        val admitted =
            PublicToolContract.admit(
                PublicToolIdentity.QUERY_SYMBOLS,
                encodePublicTool(
                    PublicToolQuerySymbols(
                        PublicToolRunAction(
                            PublicToolReferenceSource(
                                (io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                                        listOf(
                                            (io.github.amichne.kast.protocol.contract.ProtocolText.parse(
                                                    "exact:v2:opaque"
                                                ) as Refinement.Refined)
                                                .value
                                        )
                                    ) as Refinement.Refined)
                                    .value
                            ),
                            completion =
                                QueryCompletionPolicyDocument.CompleteOnly(
                                    QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1
                                ),
                        )
                    ),
                    Json,
                ),
            ) as Refinement.Refined
        val run = (admitted.value.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
        assertEquals(
            QueryCompletionPolicyDocument.CompleteOnly(QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1),
            run.completion,
        )
    }

    @Test
    fun `public boundary rejects unknown policies and models before lowering`() {
        for (policy in
            listOf(
                InvalidPublicCompletionPolicy("UNKNOWN"),
                InvalidPublicCompletionPolicy("COMPLETE_ONLY"),
                InvalidPublicCompletionPolicy("COMPLETE_ONLY", "UNKNOWN"),
                InvalidPublicCompletionPolicy("PROGRESSIVE", unexpected = true),
            )) {
            assertTrue(
                PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input(policy)) is Refinement.Rejected,
                policy.toString(),
            )
        }
    }
}

@kotlinx.serialization.Serializable
private data class InvalidPublicCompletionEnvelope(val request: InvalidPublicCompletionRun)

@kotlinx.serialization.Serializable
private data class InvalidPublicCompletionRun(
    val source: PublicToolSource,
    val completion: InvalidPublicCompletionPolicy,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS) val type: String = "RUN",
)

@kotlinx.serialization.Serializable
private data class InvalidPublicCompletionPolicy(
    val type: String,
    val model: String? = null,
    val unexpected: Boolean? = null,
)
