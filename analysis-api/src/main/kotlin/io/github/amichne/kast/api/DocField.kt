package io.github.amichne.kast.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Marks a serializable property with editorial metadata for documentation generation.
 *
 * This annotation is read at generation time by [AnalysisDocsDocument] via
 * `descriptor.getElementAnnotations(index)` to populate field descriptions
 * and examples in the generated Markdown reference pages.
 *
 * Every non-optional property on a `@Serializable` class registered in
 * [AnalysisOpenApiDocument.registerSchemas] must carry a `@DocField` with
 * a non-blank [description]. This invariant is enforced by
 * `DocFieldCoverageTest`.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class DocField(
    val description: String = "",
    val example: String = "",
)
