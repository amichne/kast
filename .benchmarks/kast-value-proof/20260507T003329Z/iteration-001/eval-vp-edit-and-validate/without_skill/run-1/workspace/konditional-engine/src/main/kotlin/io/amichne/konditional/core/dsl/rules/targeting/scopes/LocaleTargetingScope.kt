package io.amichne.konditional.core.dsl.rules.targeting.scopes

import io.amichne.konditional.context.Context
import io.amichne.konditional.context.LocaleTag
import io.amichne.konditional.core.dsl.KonditionalDsl

/**
 * Targeting mix-in for locale-based rules.
 */
@KonditionalDsl
interface LocaleTargetingScope<C : Context> {
    /**
     * Specifies which locales this rule applies to.
     *
     * The rule will only match contexts with one of the specified locales.
     *
     * @param appLocales The locales to target (use [io.amichne.konditional.context.AppLocale] or your own [LocaleTag])
     */
    fun locales(vararg appLocales: LocaleTag)
}
