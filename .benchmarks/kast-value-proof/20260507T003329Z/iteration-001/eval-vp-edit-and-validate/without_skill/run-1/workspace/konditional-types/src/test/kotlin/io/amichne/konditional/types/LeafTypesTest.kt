package io.amichne.konditional.types

import io.amichne.konditional.context.AppLocale
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.context.Version
import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.context.axis.axes
import io.amichne.konditional.core.id.StableId
import io.amichne.konditional.core.result.ParseError
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.core.result.parseFailure
import io.amichne.konditional.core.result.toParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LeafTypesTest {
    private enum class TestEnvironment(
        override val id: String,
    ) : AxisValue<TestEnvironment> {
        DEV("dev"),
        PROD("prod"),
    }

    @Test
    fun contextFactoryBuildsCoreContext() {
        val context = Context(
            locale = AppLocale.UNITED_STATES,
            platform = Platform.IOS,
            appVersion = Version.of(1, 2, 3),
            stableId = StableId.of("user-1"),
        )

        assertEquals(AppLocale.UNITED_STATES, context.locale)
        assertEquals(Platform.IOS, context.platform)
        assertEquals(Version.of(1, 2, 3), context.appVersion)
        assertEquals(StableId.of("user-1"), context.stableId)
    }

    @Test
    fun versionParsingAndComparisonAreDeterministic() {
        assertEquals(Version.of(1, 2, 0), Version.parse("1.2").getOrThrow())
        assertTrue(Version.of(1, 2, 3) > Version.of(1, 2, 2))
    }

    @Test
    fun axesGroupValuesByAxis() {
        val axis = Axis.of<TestEnvironment>()
        val values = axes(TestEnvironment.PROD)

        assertEquals(setOf(TestEnvironment.PROD), values[axis])
        assertEquals(setOf(TestEnvironment.PROD), values[TestEnvironment.PROD])
    }

    @Test
    fun parseResultPreservesTypedBoundaryFailures() {
        val result = parseFailure<Unit>(ParseError.invalidJson("bad payload")).toParseResult()

        val failure = assertIs<ParseOutcome.Failure>(result)
        assertEquals(ParseError.invalidJson("bad payload"), failure.error)
    }
}
