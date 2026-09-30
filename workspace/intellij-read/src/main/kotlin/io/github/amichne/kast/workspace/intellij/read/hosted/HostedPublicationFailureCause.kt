package io.github.amichne.kast.workspace.intellij.read.hosted

import kotlinx.serialization.Serializable

/** Final publication failure preserves its ownership or resource cause without claiming semantic staleness. */
@Serializable
enum class HostedPublicationFailureCause {
    OWNER_RETIRED,
    CLAIM_UNAVAILABLE,
    EXPIRED,
    DEPENDENCY_UNAVAILABLE,
    PUBLISHED_PAGE_MISMATCH,
    NON_ADVANCING_SUCCESSOR,
    INVALID_FITTED_PAGE,
    CAPACITY_EXCEEDED,
}

@Serializable internal data class PublicationFailureDetail(val cause: HostedPublicationFailureCause)
