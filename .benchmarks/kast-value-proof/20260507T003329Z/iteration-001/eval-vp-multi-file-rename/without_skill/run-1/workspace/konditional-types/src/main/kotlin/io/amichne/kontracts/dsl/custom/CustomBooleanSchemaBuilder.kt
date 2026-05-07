package io.amichne.kontracts.dsl.custom

import io.amichne.kontracts.dsl.BooleanSchemaBuilder
import io.amichne.kontracts.dsl.JsonSchemaBuilderDsl

/**
 * Builder for custom types that should be represented as booleans in the schema.
 */
@JsonSchemaBuilderDsl
class CustomBooleanSchemaBuilder<V : Any> @PublishedApi internal constructor() : BooleanSchemaBuilder() {
    var represent: (V.() -> Boolean)? = null
}
