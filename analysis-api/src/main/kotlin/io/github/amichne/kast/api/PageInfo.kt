package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class PageInfo(
    val truncated: Boolean,
    val nextPageToken: String? = null,
)
