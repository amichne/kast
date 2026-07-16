@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
data class ReferencesResult(
    @DocField(description = "The resolved declaration symbol, included when `includeDeclaration` was set.")
    val declaration: Symbol? = null,
    @DocField(description = "Reference locations with containing-symbol semantic evidence.")
    val references: List<ReferenceOccurrence>,
    @DocField(description = "Proof-carrying cardinality and coverage established by bounded reference work.")
    val evidence: RelationshipResultEvidence,
    @DocField(description = "Pagination metadata when results are truncated.")
    override val page: PageInfo? = null,
    @DocField(description = "Describes the scope and exhaustiveness of the search.")
    val searchScope: SearchScope? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) : PageableResult<ReferenceOccurrence> {
    val cardinality: ResultCardinality
        get() = evidence.cardinality

    override val items: List<ReferenceOccurrence>
        get() = references

    override fun withItems(
        items: List<ReferenceOccurrence>,
        page: PageInfo?,
    ): PageableResult<ReferenceOccurrence> = copy(
        references = items,
        page = page,
    )
}
