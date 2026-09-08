// Generated from query.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList

/** Defaults come from the same definitions that document the public boundary. */
internal object PublicQueryDefaults {
    val match: PublicQueryMatch = PublicQueryMatch.EXACT
    val containment: PublicQueryContainment = PublicQueryContainment.DESCENDANTS
    val kinds: BoundedProtocolList<PublicQueryDeclarationKind> = bounded(
        listOf(
            PublicQueryDeclarationKind.CLASS,
            PublicQueryDeclarationKind.FUNCTION,
            PublicQueryDeclarationKind.PROPERTY,
            PublicQueryDeclarationKind.TYPE_ALIAS,
        ),
    )
    val sourceSets: BoundedProtocolList<PublicQuerySourceSet> = bounded(
        listOf(
            PublicQuerySourceSet.MAIN,
            PublicQuerySourceSet.TEST,
        ),
    )
    val selection: BoundedProtocolList<PublicQueryField> = bounded(
        listOf(
            PublicQueryField.NAME,
            PublicQueryField.LOCATION,
        ),
    )
    val steps: BoundedProtocolList<PublicQueryStep> = bounded(emptyList())

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        when (val result = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid schema-owned query default")
        }
}
