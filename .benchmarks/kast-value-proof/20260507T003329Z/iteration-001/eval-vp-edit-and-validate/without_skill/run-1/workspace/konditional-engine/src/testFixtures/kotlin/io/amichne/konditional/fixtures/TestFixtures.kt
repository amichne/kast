package io.amichne.konditional.fixtures

import io.amichne.konditional.context.AppLocale
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.context.Version
import io.amichne.konditional.context.axis.Axis
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.context.axis.Axes
import io.amichne.konditional.context.axis.axes
import io.amichne.konditional.core.id.StableId
import io.amichne.konditional.core.types.Konstrained
import io.amichne.kontracts.dsl.of
import io.amichne.kontracts.dsl.schema

enum class TestEnvironment(
    override val id: String,
) : AxisValue<TestEnvironment> {
    DEV("dev"),
    PROD("prod"),
}

object TestAxes {
    val Environment = Axis.of<TestEnvironment>()
}

data class TestContext(
    override val locale: AppLocale = AppLocale.UNITED_STATES,
    override val platform: Platform = Platform.IOS,
    override val appVersion: Version = Version.of(1, 0, 0),
    override val stableId: StableId = StableId.of("fixture-user"),
    override val axes: Axes = Axes.EMPTY,
) : Context,
    Context.LocaleContext,
    Context.PlatformContext,
    Context.VersionContext,
    Context.StableIdContext

fun productionContext(
    stableId: String = "fixture-user",
): TestContext =
    TestContext(
        stableId = StableId.of(stableId),
        axes = axes(TestEnvironment.PROD),
    )

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffMs: Double = 1000.0,
    val enabled: Boolean = true,
    val mode: String = "exponential",
) : Konstrained.Object {
    val schema =
        schema {
            ::maxAttempts of { minimum = 1 }
            ::backoffMs of { minimum = 0.0 }
            ::enabled of { default = true }
            ::mode of { minLength = 1 }
        }
}
