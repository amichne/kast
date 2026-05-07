package io.amichne.kontracts.dsl.custom

import io.amichne.kontracts.dsl.DoubleSchemaBuilder
import io.amichne.kontracts.dsl.JsonSchemaBuilderDsl

/**
 * Builder for custom types that should be represented as doubles in the schema.
 */
@JsonSchemaBuilderDsl
class CustomDoubleSchemaBuilder<V : Any> @PublishedApi internal constructor() : DoubleSchemaBuilder() {
    var represent: (V.() -> Double)? = null
}
