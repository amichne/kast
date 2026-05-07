package io.amichne.konditional.json

import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.instance.ConfigurationMetadata
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.fixtures.RetryPolicy
import io.amichne.konditional.fixtures.TestContext
import io.amichne.konditional.serialization.fromJson
import io.amichne.konditional.serialization.toJson
import io.amichne.konditional.values.FeatureId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NamespaceJsonTest {
    private enum class Theme {
        LIGHT,
        DARK,
    }

    @Test
    fun strictRoundTripPreservesPrimitiveEnumAndKonstrainedValues() {
        val namespace = object : Namespace.TestNamespaceFacade("json-roundtrip") {
            val enabled by boolean<Context>(default = false)
            val theme by enum<Theme, Context>(default = Theme.LIGHT)
            val retryPolicy by custom<RetryPolicy, Context>(default = RetryPolicy())
        }

        namespace.load(
            Configuration(
                flags = mapOf(
                    namespace.enabled to FlagDefinition(feature = namespace.enabled, bounds = emptyList(), defaultValue = true),
                    namespace.theme to FlagDefinition(feature = namespace.theme, bounds = emptyList(), defaultValue = Theme.DARK),
                    namespace.retryPolicy to FlagDefinition(
                        feature = namespace.retryPolicy,
                        bounds = emptyList(),
                        defaultValue = RetryPolicy(maxAttempts = 7, backoffMs = 250.0, enabled = false, mode = "linear"),
                    ),
                ),
                metadata = ConfigurationMetadata(version = "v1"),
            ),
        )

        val json = namespace.toJson()
        val result = namespace.fromJson(json)

        assertIs<ParseOutcome.Success<Configuration>>(result)
        assertEquals(true, namespace.enabled.evaluate(TestContext()))
        assertEquals(Theme.DARK, namespace.theme.evaluate(TestContext()))
        assertEquals(RetryPolicy(maxAttempts = 7, backoffMs = 250.0, enabled = false, mode = "linear"), namespace.retryPolicy.evaluate(TestContext()))
    }

    @Test
    fun unknownFeatureKeyFailsStrictlyAndLeavesCurrentStateUntouched() {
        val namespace = object : Namespace.TestNamespaceFacade("json-strict") {
            val enabled by boolean<Context>(default = false)
        }

        val previous = namespace.enabled.evaluate(TestContext())
        val unknownKey = FeatureId.create(namespace.id, "missing-flag")
        val json =
            """
            {
              "flags": [
                {
                  "key": "${unknownKey.plainId}",
                  "defaultValue": { "type": "BOOLEAN", "value": true }
                }
              ]
            }
            """.trimIndent()

        val failure = assertIs<ParseOutcome.Failure>(namespace.fromJson(json))
        assertIs<ParseError.FeatureNotFound>(failure.error)
        assertEquals(previous, namespace.enabled.evaluate(TestContext()))
    }

    @Test
    fun invalidKonstrainedPayloadFailsStrictly() {
        val namespace = object : Namespace.TestNamespaceFacade("json-konstrained") {
            val retryPolicy by custom<RetryPolicy, Context>(default = RetryPolicy())
        }

        val json =
            """
            {
              "flags": [
                {
                  "key": "${namespace.retryPolicy.id.plainId}",
                  "defaultValue": {
                    "type": "DATA_CLASS",
                    "value": {
                      "maxAttempts": "oops",
                      "backoffMs": 1000.0,
                      "enabled": true,
                      "mode": "exp"
                    },
                    "dataClassName": "${RetryPolicy::class.java.name}"
                  }
                }
              ]
            }
            """.trimIndent()

        val failure = assertIs<ParseOutcome.Failure>(namespace.fromJson(json))
        assertTrue(
            failure.error is ParseError.InvalidSnapshot || failure.error is ParseError.InvalidValue,
        )
        assertEquals(RetryPolicy(), namespace.retryPolicy.evaluate(TestContext()))
    }

    @Test
    fun malformedJsonFailsWithTypedBoundaryError() {
        val namespace = object : Namespace.TestNamespaceFacade("json-invalid") {
            val enabled by boolean<Context>(default = false)
        }

        val failure = assertIs<ParseOutcome.Failure>(namespace.fromJson("{ not-json"))

        assertIs<ParseError.InvalidJson>(failure.error)
        assertEquals(false, namespace.enabled.evaluate(TestContext()))
    }
}
