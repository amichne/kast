package io.amichne.konditional.internal.builders.versions

import io.amichne.konditional.context.Version
import io.amichne.konditional.core.dsl.VersionRangeScope
import io.amichne.konditional.rules.versions.FullyBound
import io.amichne.konditional.rules.versions.LeftBound
import io.amichne.konditional.rules.versions.RightBound
import io.amichne.konditional.rules.versions.Unbounded
import io.amichne.konditional.rules.versions.VersionRange

/**
 * Internal implementation of [VersionRangeScope].
 *
 * This class is the internal implementation of the version range configuration DSL scope.
 * Users interact with the public [VersionRangeScope] interface,
 * not this implementation directly.
 *
 * @constructor Internal constructor - users cannot instantiate this class directly.
 */
@PublishedApi
internal data class VersionRangeBuilder(
    private var leftBound: Version = Version.default,
    private var rightBound: Version = Version.default,
) : VersionRangeScope {

    /**
     * Implementation of [VersionRangeScope.min].
     */
    override fun min(
        major: Int,
        minor: Int,
        patch: Int,
    ) {
        leftBound = Version(major, minor, patch)
    }

    /**
     * Implementation of [VersionRangeScope.max].
     */
    override fun max(
        major: Int,
        minor: Int,
        patch: Int,
    ) {
        rightBound = Version(major, minor, patch)
    }

    fun build(): VersionRange =
        when {
            leftBound != Version.default && rightBound != Version.default -> FullyBound(leftBound, rightBound)
            leftBound == Version.default -> RightBound(rightBound)
            rightBound == Version.default -> LeftBound(leftBound)
            else -> Unbounded
        }
}
