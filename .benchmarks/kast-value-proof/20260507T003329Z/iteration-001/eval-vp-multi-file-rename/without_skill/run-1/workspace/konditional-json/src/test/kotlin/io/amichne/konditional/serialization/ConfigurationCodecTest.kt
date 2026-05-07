package io.amichne.konditional.serialization

import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.fixtures.RetryPolicy
import io.amichne.konditional.fixtures.TestContext
import io.amichne.konditional.values.FeatureId
import io.amichne.konditional.values.NamespaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigurationCodecTest {
    @Test
    fun `toJson fromJson round trips primitives enums and konstrained values`() {
        val context = TestContext(platform = Platform.IOS)

        assertTrue(SerializableFlags.enabled.evaluate(context))
        assertEquals(Theme.DARK, SerializableFlags.theme.evaluate(context))
        assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(context))

        val json = SerializableFlags.toJson()
        val result = SerializableFlags.fromJson(json)

        assertIs<ParseOutcome.Success<*>>(result)
        assertTrue(SerializableFlags.enabled.evaluate(context))
        assertEquals(Theme.DARK, SerializableFlags.theme.evaluate(context))
        assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(context))
    }

    @Test
    fun `invalid json and unknown feature keys fail strictly`() {
        assertIs<ParseOutcome.Failure>(SerializableFlags.fromJson("{not-json"))

        val unknownFeatureId = FeatureId.create(NamespaceId("json-main"), "unknown")
        val unknownKeyJson = SerializableFlags.toJson().replace(
            SerializableFlags.enabled.id.toString(),
            unknownFeatureId.toString(),
        )
        val result = SerializableFlags.fromJson(unknownKeyJson)

        assertEquals(
            ParseError.featureNotFound(unknownFeatureId),
            assertIs<ParseOutcome.Failure>(result).error,
        )
    }

    @Test
    fun `invalid payload does not mutate namespace state or leak across namespaces`() {
        val baseline = SerializableFlags.toJson()
        val invalidPayload = baseline.replace("\"mode\": \"ios\"", "\"mode\": 42")

        val failure = SerializableFlags.fromJson(invalidPayload)

        assertIs<ParseOutcome.Failure>(failure)
        assertEquals(RetryPolicy(mode = "ios"), SerializableFlags.uiConfig.evaluate(TestContext(platform = Platform.IOS)))
        assertFalse(SecondaryFlags.enabled.evaluate(TestContext(platform = Platform.IOS)))
    }

    private object SerializableFlags : Namespace.TestNamespaceFacade("json-main") {
        val enabled by boolean<TestContext>(default = false) {
            rule(true) { ios() }
        }

        val theme by enum<Theme, TestContext>(default = Theme.LIGHT) {
            rule(Theme.DARK) { ios() }
        }

        val uiConfig by custom<RetryPolicy, TestContext>(default = RetryPolicy()) {
            rule(RetryPolicy(mode = "ios")) { ios() }
        }
    }

    private object SecondaryFlags : Namespace.TestNamespaceFacade("json-secondary") {
        val enabled by boolean<Context>(default = false)
    }

    private enum class Theme {
        LIGHT,
        DARK,
    }
}
