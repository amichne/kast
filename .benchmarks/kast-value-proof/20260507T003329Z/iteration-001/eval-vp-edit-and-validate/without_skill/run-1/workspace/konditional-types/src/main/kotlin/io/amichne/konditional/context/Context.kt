package io.amichne.konditional.context

import io.amichne.konditional.context.axis.Axes
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.core.id.StableId

/**
 * Represents the execution context for feature flag evaluation.
 *
 * This interface defines the base contextual information required for evaluating
 * feature flags. It provides axis-based targeting and can be extended with mixin
 * interfaces to opt into standard targeting dimensions (locale, platform, version)
 * and stable identifiers for deterministic rampUp bucketing.
 *
 * `locale` and `platform` are modeled as stable identifiers via [LocaleTag] and [PlatformTag].
 * Use the provided [AppLocale] and [Platform] enums, or supply your own types with stable ids.
 *
 * You can extend this interface to add custom fields for domain-specific targeting:
 * ```kotlin
 * data class EnterpriseContext(
 *     override val locale: AppLocale,
 *     override val platform: Platform,
 *     override val appVersion: Version,
 *     override val stableId: StableId,
 *     val organizationId: String,
 *     val subscriptionTier: SubscriptionTier,
 * ) : Context, Context.LocaleContext, Context.PlatformContext, Context.VersionContext, Context.StableIdContext
 * ```
 *
 * @see LocaleContext
 * @see PlatformContext
 * @see VersionContext
 * @see StableIdContext
 */
interface Context {
    /**
     * Axis values for this context (environment, region, tenant, etc.).
     *
     * Provides access to dimensional values for more granular rule targeting
     * beyond the standard locale, platform, and version criteria.
     *
     * Defaults to [Axes.EMPTY] for simple contexts that don't use axis values.
     */
    val axes: Axes
        get() = Axes.EMPTY

    /**
     * Mix-in for locales when locale-based targeting is needed.
     */
    interface LocaleContext : Context {
        val locale: LocaleTag
    }

    /**
     * Mix-in for platforms when platform-based targeting is needed.
     */
    interface PlatformContext : Context {
        val platform: PlatformTag
    }

    /**
     * Mix-in for versions when version-based targeting is needed.
     */
    interface VersionContext : Context {
        val appVersion: Version
    }

    /**
     * Mix-in for stable IDs when rampUp bucketing or allowlists are needed.
     */
    interface StableIdContext : Context {
        val stableId: StableId
    }

    data class Core(
        override val locale: LocaleTag,
        override val platform: PlatformTag,
        override val appVersion: Version,
        override val stableId: StableId,
    ) : LocaleContext, PlatformContext, VersionContext, StableIdContext

    companion object {

        /**
         * Creates a basic Context instance with the specified properties.
         *
         * This factory method provides a convenient way to create Context instances
         * without defining a custom implementation class.
         *
         * @param locale The application locale
         * @param platform The platform (iOS, Android, Web, etc.)
         * @param appVersion The semantic version create the application
         * @param stableId A stable, unique identifier for deterministic bucketing
         * @return A Context instance with the specified properties
         */
        operator fun invoke(
            locale: LocaleTag,
            platform: PlatformTag,
            appVersion: Version,
            stableId: StableId,
        ): Core = Core(locale, platform, appVersion, stableId)

        /**
         * Generic access to axis values by axis ID.
         *
         * Consumers should prefer typed access via [Axes.get] and [io.amichne.konditional.context.axis.Axis.of]
         * rather than calling this directly.
         *
         * @param axisId The unique identifier create the axis
         * @return The values for that axis, or empty if not present
         */
        fun Context.getAxisValue(axisId: String): Set<AxisValue<*>> =
            axes[axisId]
    }
}
