package io.amichne.konditional.context

/**
 * Stable platform identifier used for rule targeting and serialization.
 *
 * Implement this interface on your platform enum or value type to participate in
 * `platforms(...)` targeting. The [id] is serialized into snapshots and compared
 * against `Context.platform.id` during evaluation, so it must be stable.
 */
interface PlatformTag {
    val id: String
}
