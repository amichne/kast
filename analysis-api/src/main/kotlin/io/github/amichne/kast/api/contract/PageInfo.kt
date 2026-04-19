@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class PageInfo(
    @DocField(description = "True when results exceed the maximum and additional pages are available.")
    val truncated: Boolean,
    @DocField(description = "Opaque token to pass in the next request for the next page of results.")
    val nextPageToken: String? = null,
)
