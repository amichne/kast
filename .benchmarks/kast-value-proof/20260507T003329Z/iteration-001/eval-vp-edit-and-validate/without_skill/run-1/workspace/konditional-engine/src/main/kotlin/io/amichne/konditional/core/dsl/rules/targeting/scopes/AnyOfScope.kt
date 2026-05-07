package io.amichne.konditional.core.dsl.rules.targeting.scopes

import io.amichne.konditional.context.Context
import io.amichne.konditional.core.dsl.KonditionalDsl

/**
 * DSL scope for [io.amichne.konditional.rules.targeting.Targeting.AnyOf] blocks.
 *
 * Exposes all targeting dimension mix-ins without rule-metadata concerns
 * ([StableIdTargetingScope], [io.amichne.konditional.core.dsl.rules.NoteScope], rampUp).
 *
 * Each call to a targeting method inside an `anyOf { }` block adds one OR branch.
 * The complete OR group is then appended as a single [io.amichne.konditional.rules.targeting.Targeting.AnyOf]
 * leaf in the enclosing [io.amichne.konditional.rules.targeting.Targeting.All].
 *
 * [whenContext] from [ExtensionTargetingScope] is available automatically as it
 * is an extension function on that interface.
 */
@KonditionalDsl
interface AnyOfScope<C : Context> :
    LocaleTargetingScope<C>,
    PlatformTargetingScope<C>,
    VersionTargetingScope<C>,
    AxisTargetingScope<C>,
    ExtensionTargetingScope<C>
