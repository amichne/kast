package io.amichne.konditional.context

/**
 * Stable locale identifier used for rule targeting and serialization.
 *
 * Implement this interface on your locale enum or value type to participate in
 * `locales(...)` targeting. The [id] is serialized into snapshots and compared
 * against `Context.locale.id` during evaluation, so it must be stable.
 */
interface LocaleTag {
    val id: String
}
