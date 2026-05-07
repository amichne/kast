package io.amichne.konditional.context

/**
 * Platform
 *
 * @constructor Create empty Platform
 */
enum class Platform : PlatformTag {
    IOS,
    ANDROID;

    override val id: String = name
}
