package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KastResolveResponseTest {
    private val json = Json

    @Test
    fun `existing success and failure discriminators remain compatible`() {
        val success = json.decodeFromString<KastResolveResponse>(
            """
            {
              "type":"RESOLVE_SUCCESS",
              "ok":true,
              "query":{"workspaceRoot":"/workspace","symbol":"sample.Parser"},
              "symbol":$symbolJson,
              "filePath":"/workspace/Parser.kt",
              "offset":10,
              "candidate":{"line":1,"column":1,"context":"class Parser"},
              "candidateCount":1,
              "alternatives":[],
              "logFile":"/tmp/kast.log"
            }
            """.trimIndent(),
        )
        val failure = json.decodeFromString<KastResolveResponse>(
            """
            {
              "type":"RESOLVE_FAILURE",
              "ok":false,
              "stage":"resolve",
              "message":"backend failed",
              "query":{"workspaceRoot":"/workspace","symbol":"sample.Parser"},
              "logFile":"/tmp/kast.log"
            }
            """.trimIndent(),
        )

        assertInstanceOf(KastResolveSuccessResponse::class.java, success)
        assertInstanceOf(KastResolveFailureResponse::class.java, failure)
    }

    @Test
    fun `expected exact outcomes decode distinctly`() {
        val notFound = json.decodeFromString<KastResolveResponse>(
            """
            {
              "type":"RESOLVE_NOT_FOUND",
              "ok":true,
              "source":"compiler",
              "query":{"workspaceRoot":"/workspace","symbol":"Missing"},
              "logFile":"/tmp/kast.log"
            }
            """.trimIndent(),
        )
        val ambiguous = json.decodeFromString<KastResolveResponse>(
            """
            {
              "type":"RESOLVE_AMBIGUOUS",
              "ok":true,
              "source":"compiler",
              "query":{"workspaceRoot":"/workspace","symbol":"parse"},
              "candidates":[$symbolJson,$symbolJson],
              "logFile":"/tmp/kast.log"
            }
            """.trimIndent(),
        )

        val typedNotFound = assertInstanceOf(KastResolveNotFoundResponse::class.java, notFound)
        val typedAmbiguous = assertInstanceOf(KastResolveAmbiguousResponse::class.java, ambiguous)
        assertEquals(KastResolveResponse.Source.COMPILER, typedNotFound.source)
        assertEquals(2, typedAmbiguous.candidates.size)
    }

    private companion object {
        val symbolJson =
            """
            {
              "fqName":"sample.Parser",
              "kind":"CLASS",
              "location":{
                "filePath":"/workspace/Parser.kt",
                "startOffset":10,
                "endOffset":16,
                "startLine":1,
                "startColumn":1,
                "preview":"class Parser"
              }
            }
            """.trimIndent()
    }
}
