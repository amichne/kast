package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Marks a serializable property with editorial metadata for documentation generation.
 *
 * This annotation is read at generation time by [DocsDocument] via
 * `descriptor.getElementAnnotations(index)` to populate field descriptions
 * and examples in the generated Markdown reference pages.
 *
 * Every non-optional property on a `@Serializable` class registered in
 * [OpenApiDocument.registerSchemas] must carry a `@DocField` with
 * a non-blank [description]. This invariant is enforced by
 * `DocFieldCoverageTest`.
 *
 * ## [defaultValue] vs [serverManaged]
 *
 * Use [defaultValue] for user-configurable optional inputs (e.g. `"100"`, `"false"`,
 * `"emptyList()"`). The generator renders these as `` `#!kotlin field: Type = value` ``
 * with a tooltip.
 *
 * Use `serverManaged = true` for output fields that are always populated by the server
 * (e.g. `schemaVersion`). The generator renders these as `` `#!kotlin field: Type` ``
 * with no optionality marker and no default tooltip, because the value is never
 * caller-supplied.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class DocField(
    val description: String = "",
    val example: String = "",
    val defaultValue: String = "",
    /** When true, renders as a plain non-optional type with no default tooltip. */
    val serverManaged: Boolean = false,
)
