// Generated from query.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText

/** Defaults come from the same definitions that document the public boundary. */
internal object PublicQueryDefaults {
    val match: PublicQueryMatch = PublicQueryMatch.EXACT
    val containment: PublicQueryContainment = PublicQueryContainment.RECURSIVE
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind> = bounded(
        listOf(
            PublicQueryDeclarationKind.CLASS,
            PublicQueryDeclarationKind.FUNCTION,
            PublicQueryDeclarationKind.PROPERTY,
            PublicQueryDeclarationKind.TYPE_ALIAS,
        ),
    )
    val sourceSets: BoundedProtocolList<ProtocolText> = bounded(
        listOf(
            text("main"),
            text("test"),
        ),
    )
    val selection: BoundedProtocolList<PublicQueryField> = bounded(
        listOf(
            PublicQueryField.NAME,
            PublicQueryField.LOCATION,
        ),
    )
    val steps: BoundedProtocolList<PublicQueryStep> = bounded(emptyList())

    private fun text(value: String): ProtocolText =
        when (val result = ProtocolText.parse(value)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid schema-owned query default")
        }

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        when (val result = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid schema-owned query default")
        }
}
