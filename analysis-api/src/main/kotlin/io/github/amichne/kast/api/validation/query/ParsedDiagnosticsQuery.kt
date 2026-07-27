package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt

data class ParsedDiagnosticsQuery(
    val filePaths: NonEmptyList<NormalizedPath>,
    override val maxResults: PositiveInt,
    val pageToken: DiagnosticPageToken?,
) : BoundedQuery
