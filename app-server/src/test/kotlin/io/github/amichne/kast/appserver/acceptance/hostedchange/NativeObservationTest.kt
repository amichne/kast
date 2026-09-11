package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.BrokerProcessExecution
import io.github.amichne.kast.appserver.provider.BrokerProcessFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NativeObservationTest {
    @Test
    fun `process success retains structural live evidence without payload fields or values`() {
        val raw =
            """
            |{"status":"complete","live":{},"changes":[{"diff":"private-source"}],
            |"private-field":"private-value"}
            """
                .trimMargin()
        val observed = processObservation(BrokerProcessExecution.Completed(0, raw, "private stderr"))
        assertEquals(JsonPrimitive("zero-exit"), observed["outcome"])
        assertEquals(Json.parseToJsonElement("""["CHANGES","LIVE","STATUS","UNKNOWN"]"""), observed["fields"])
        for (secret in listOf("private-source", "private-field", "private-value", "private stderr")) {
            assertFalse(observed.toString().contains(secret))
        }
    }

    @Test
    fun `process failure reports the closed adapter outcome`() {
        val observed = processObservation(BrokerProcessExecution.Rejected(BrokerProcessFailure.TIMED_OUT))
        assertEquals(JsonPrimitive("process-rejected"), observed["outcome"])
        assertEquals(JsonPrimitive("TIMED_OUT"), observed["failure"])
    }

    @Test
    fun `protocol rejection retains finite reason when presentation has multiple content items`() {
        val document = buildJsonObject {
            put(
                "result",
                buildJsonObject {
                    put(
                        "contentItems",
                        buildJsonArray {
                            add(buildJsonObject { put("text", "private-source") })
                            add(
                                buildJsonObject {
                                    put(
                                        "text",
                                        """{"document":{"status":"rejected","reason":"exact-symbol-required"}}""",
                                    )
                                }
                            )
                        },
                    )
                },
            )
        }
        val observed = protocolObservation(document.toString())
        assertEquals(JsonPrimitive("EXACT_SYMBOL_REQUIRED"), observed["canonicalRejection"])
        assertFalse(observed.toString().contains("private-source"))
    }
}
