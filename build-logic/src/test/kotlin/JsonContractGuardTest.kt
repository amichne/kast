import conventions.jsoncontracts.JsonContractAllowance
import conventions.jsoncontracts.JsonContractBaselineDocument
import conventions.jsoncontracts.JsonContractBaselineFailure
import conventions.jsoncontracts.JsonContractEvidence
import conventions.jsoncontracts.JsonContractExpressionKind
import conventions.jsoncontracts.JsonContractGuard
import conventions.jsoncontracts.JsonContractSource
import conventions.jsoncontracts.JsonContractViolation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonContractGuardTest {
    private val guard = JsonContractGuard()
    private val path = "module/src/main/kotlin/Fixture.kt"
    private val emptyBaseline = Json.encodeToString(JsonContractBaselineDocument(1, emptyList()))

    @Test
    fun `imports aliases star imports and qualified constructors identify manual expressions`() {
        val source =
            """
            import kotlinx.serialization.json.buildJsonObject as objectBuilder
            import kotlinx.serialization.json.JsonObject as ObjectValue
            import kotlinx.serialization.json.*
            fun contract() = objectBuilder {
                put("first", ObjectValue(emptyMap()))
                put("second", JsonArray(emptyList()))
                put("third", kotlinx.serialization.json.buildJsonArray { })
            }
            """
                .trimIndent()
        val report = verify(source)
        assertEquals(JsonContractEvidence.KOTLIN_PSI_SYNTAX, report.evidence)
        assertEquals(4, report.findings.sumOf { it.count })
        assertEquals(
            setOf(
                JsonContractExpressionKind.JSON_BUILDER,
                JsonContractExpressionKind.JSON_OBJECT,
                JsonContractExpressionKind.JSON_ARRAY,
            ),
            report.findings.map { it.fingerprint.kind }.toSet(),
        )
        assertEquals(4, report.violations.size)
    }

    @Test
    fun `extending a baselined builder is a new expression while comments and formatting are stable`() {
        val original =
            """
            import kotlinx.serialization.json.buildJsonObject
                        fun contract() = buildJsonObject { put("name", "before") }
            """
                .trimIndent()
        val first = verify(original)
        val finding = first.findings.single()
        val baseline =
            baseline(JsonContractAllowance(finding.fingerprint, 1, "Existing fixed request pending DTO migration."))
        assertTrue(verify(original, baseline).violations.isEmpty())
        val formatted = original.replace("{ put", "{ /* comment about JSON */\n    put").replace(" }", "\n}")
        assertEquals(finding.fingerprint, verify(formatted).findings.single().fingerprint)
        assertTrue(verify(formatted, baseline).violations.isEmpty())
        val changed = original.replace("put(\"name\", \"before\")", "put(\"name\", \"before\"); put(\"added\", true)")
        assertTrue(verify(changed, baseline).violations.any { it is JsonContractViolation.Unapproved })
        assertTrue(verify(changed, baseline).violations.any { it is JsonContractViolation.StaleAllowance })
    }

    @Test
    fun `duplicating an allowed expression in its named scope exceeds its count`() {
        val source =
            """
            import kotlinx.serialization.json.JsonObject
                        fun contract() { JsonObject(emptyMap()) }
            """
                .trimIndent()
        val finding = verify(source).findings.single()
        val baseline =
            baseline(JsonContractAllowance(finding.fingerprint, 1, "Existing opaque boundary under migration."))
        val duplicate = source.replace("JsonObject(emptyMap())", "JsonObject(emptyMap()); JsonObject(emptyMap())")
        val violation =
            assertInstanceOf(
                JsonContractViolation.CountExceeded::class.java,
                verify(duplicate, baseline).violations.single(),
            )
        assertEquals(2, violation.finding.count)
        assertEquals(1, violation.allowed)
    }

    @Test
    fun `moving a manual expression to another named scope needs a distinct allowance`() {
        val source =
            """
            import kotlinx.serialization.json.JsonObject
                        class Boundary { fun first() = JsonObject(emptyMap()) }
            """
                .trimIndent()
        val first = verify(source).findings.single().fingerprint
        val moved = verify(source.replace("first", "second")).findings.single().fingerprint
        assertEquals(first.sha256, moved.sha256)
        assertNotEquals(first.scope, moved.scope)
    }

    @Test
    fun `direct ordinary raw and interpolated JSON strings are found without scanning snippet text`() {
        val triple = "\"\"\""
        val source =
            """
            fun contract(name: String) {
                val ordinary = "{\"status\":\"ready\"}"
                val raw = ${triple}{"status":"ready"}$triple
                val interpolated = ${triple}{"status":"${'$'}name"}$triple
                val snippet = "fun demo() = buildJsonObject { }"
                // buildJsonObject { put("ignored", "comment") }
            }
        """
                .trimIndent()
        val report = verify(source)
        assertEquals(3, report.findings.sumOf { it.count })
        assertEquals(
            setOf(JsonContractExpressionKind.JSON_LITERAL),
            report.findings.map { it.fingerprint.kind }.toSet(),
        )
    }

    @Test
    fun `typed serialization comments and unrelated same named functions pass`() {
        val source =
            """
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.Json
            @Serializable data class Request(val status: String)
            fun contract() = Json.encodeToString(Request("ready"))
            fun buildJsonObject(block: () -> Unit) = block()
            fun unrelated() = buildJsonObject { println("not a JSON contract") }
            /* {"comment":"ignored"} */
            """
                .trimIndent()
        assertTrue(verify(source).findings.isEmpty())
        assertTrue(verify(source).violations.isEmpty())
    }

    @Test
    fun `malformed Kotlin is rejected instead of reporting an empty scan`() {
        assertInstanceOf(JsonContractViolation.InvalidSource::class.java, verify("fun broken( {").violations.single())
    }

    @Test
    fun `invalid or duplicate baseline allowances fail closed`() {
        val source = "import kotlinx.serialization.json.JsonObject\nfun contract() = JsonObject(emptyMap())"
        val fingerprint = verify(source).findings.single().fingerprint
        val allowance = JsonContractAllowance(fingerprint, 1, "Explicit existing request debt.")
        val invalid =
            listOf(
                "not JSON" to JsonContractBaselineFailure.INVALID_DOCUMENT,
                Json.encodeToString(JsonContractBaselineDocument(2, emptyList())) to
                    JsonContractBaselineFailure.UNSUPPORTED_VERSION,
                baseline(allowance.copy(fingerprint = fingerprint.copy(path = "../escape.kt"))) to
                    JsonContractBaselineFailure.INVALID_PATH,
                baseline(allowance.copy(fingerprint = fingerprint.copy(scope = ""))) to
                    JsonContractBaselineFailure.INVALID_SCOPE,
                baseline(allowance.copy(fingerprint = fingerprint.copy(sha256 = "not-a-hash"))) to
                    JsonContractBaselineFailure.INVALID_HASH,
                baseline(allowance.copy(count = 0)) to JsonContractBaselineFailure.INVALID_COUNT,
                baseline(allowance.copy(justification = "")) to JsonContractBaselineFailure.INVALID_JUSTIFICATION,
                baseline(allowance, allowance) to JsonContractBaselineFailure.DUPLICATE_FINGERPRINT,
            )
        invalid.forEach { (document, reason) ->
            val violation =
                assertInstanceOf(
                    JsonContractViolation.InvalidBaseline::class.java,
                    verify(source, document).violations.single(),
                )
            assertEquals(reason, violation.reason)
        }
    }

    @Test
    fun `removed expressions and reduced duplicate counts make allowances stale`() {
        val source =
            "import kotlinx.serialization.json.JsonObject\nfun contract() { JsonObject(emptyMap()); JsonObject(emptyMap()) }"
        val finding = verify(source).findings.single()
        val baseline =
            baseline(JsonContractAllowance(finding.fingerprint, 2, "Existing duplicate fixtures pending migration."))
        val reduced = source.replace("; JsonObject(emptyMap())", "")
        val stale =
            assertInstanceOf(
                JsonContractViolation.StaleAllowance::class.java,
                verify(reduced, baseline).violations.single(),
            )
        assertEquals(1, stale.observedCount)
        val removed = guard.verify(emptyList(), baseline)
        assertEquals(
            0,
            assertInstanceOf(JsonContractViolation.StaleAllowance::class.java, removed.violations.single())
                .observedCount,
        )
    }

    private fun verify(source: String, baseline: String = emptyBaseline) =
        guard.verify(listOf(JsonContractSource(path, source)), baseline)

    private fun baseline(vararg allowances: JsonContractAllowance): String =
        Json.encodeToString(JsonContractBaselineDocument(1, allowances.toList()))
}
