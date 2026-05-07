import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.AppLocale
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.context.Version
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.id.StableId
import io.amichne.konditional.core.result.ParseOutcome
import io.amichne.konditional.serialization.fromJson
import io.amichne.konditional.serialization.toJson
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmokeTest {
    object Flags : Namespace("smoke") {
        val enabled by boolean<Context>(default = false) {
            rule(true) { platforms(Platform.IOS) }
        }
    }

    @Test
    fun fullLifecycle() {
        val context = Context(
            locale = AppLocale.UNITED_STATES,
            platform = Platform.IOS,
            appVersion = Version.of(1, 0, 0),
            stableId = StableId.of("user-1"),
        )

        assertTrue(Flags.enabled.evaluate(context))

        val json = Flags.toJson()
        val result = Flags.fromJson(json)

        assertIs<ParseOutcome.Success<*>>(result)
        assertTrue(Flags.enabled.evaluate(context))
    }
}
