package io.amichne.konditional.core.dsl.rules.targeting.scopes

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.context.PlatformTag
import io.amichne.konditional.core.dsl.KonditionalDsl

/**
 * Targeting mix-in for platform-based rules.
 */
@KonditionalDsl
interface PlatformTargetingScope<C : Context> {
    /**
     * Sugar for targeting iOS.
     *
     * Equivalent to `platforms(Platform.IOS)`.
     */
    fun ios() = platforms(Platform.IOS)

    /**
     * Sugar for targeting Android.
     *
     * Equivalent to `platforms(Platform.ANDROID)`.
     */
    fun android() = platforms(Platform.ANDROID)

    /**
     * Specifies which platforms this rule applies to.
     *
     * The rule will only match contexts with one of the specified platforms.
     *
     * @param ps The platforms to target (use [Platform] or your own [PlatformTag])
     */
    fun platforms(vararg ps: PlatformTag)
}
