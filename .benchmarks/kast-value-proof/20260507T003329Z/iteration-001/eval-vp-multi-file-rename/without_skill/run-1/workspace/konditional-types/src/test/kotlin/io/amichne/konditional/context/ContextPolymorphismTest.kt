package io.amichne.konditional.context

import io.amichne.konditional.context.axis.Axes
import io.amichne.konditional.core.id.StableId
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextPolymorphismTest {
    @Test
    fun `custom contexts can compose the standard mixins`() {
        val base = Context(
            locale = AppLocale.UNITED_STATES,
            platform = Platform.ANDROID,
            appVersion = Version.of(2, 1, 0),
            stableId = StableId.of("context-user"),
        )

        val composite = CompositeContext(base = base, sessionId = "session-1")

        assertEquals(AppLocale.UNITED_STATES, composite.locale)
        assertEquals(Platform.ANDROID, composite.platform)
        assertEquals(Version.of(2, 1, 0), composite.appVersion)
        assertEquals(base.stableId.hexId.id, composite.stableId.hexId.id)
        assertEquals(Axes.EMPTY, composite.axes)
    }
}

private data class CompositeContext(
    val base: Context.Core,
    val sessionId: String,
) : Context,
    Context.LocaleContext,
    Context.PlatformContext,
    Context.VersionContext,
    Context.StableIdContext {
    override val locale = base.locale
    override val platform = base.platform
    override val appVersion = base.appVersion
    override val stableId = base.stableId
}
