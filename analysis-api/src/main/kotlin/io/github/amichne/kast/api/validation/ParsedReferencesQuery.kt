package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.PositiveInt

data class ParsedReferencesQuery(
    override val position: ParsedFilePosition,
    val includeDeclaration: Boolean,
    val includeUsageSiteScope: Boolean,
    override val maxResults: PositiveInt,
    val pageToken: ReferencePageToken?,
) : PositionQuery, BoundedQuery
