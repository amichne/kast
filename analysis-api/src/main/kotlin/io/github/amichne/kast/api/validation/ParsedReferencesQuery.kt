package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector

data class ParsedReferencesQuery(
    override val position: ParsedFilePosition,
    val includeDeclaration: Boolean,
    val includeUsageSiteScope: Boolean,
    override val maxResults: PositiveInt,
    val pageToken: ReferencePageToken?,
    val selector: KastExactSymbolSelector?,
) : PositionQuery, BoundedQuery
