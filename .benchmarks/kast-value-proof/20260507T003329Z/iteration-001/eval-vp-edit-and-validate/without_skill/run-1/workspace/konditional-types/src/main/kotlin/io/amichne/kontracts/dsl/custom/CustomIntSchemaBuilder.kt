package io.amichne.kontracts.dsl.custom

import io.amichne.kontracts.dsl.IntSchemaBuilder
import io.amichne.kontracts.dsl.JsonSchemaBuilderDsl

/**
 * Builder for custom types that should be represented as integers in the schema.
 */
@JsonSchemaBuilderDsl
class CustomIntSchemaBuilder<V : Any> @PublishedApi internal constructor() : IntSchemaBuilder() {
    var represent: (V.() -> Int)? = null
}
