package io.amichne.kontracts.dsl.custom

import io.amichne.kontracts.dsl.JsonSchemaBuilderDsl
import io.amichne.kontracts.dsl.StringSchemaBuilder

/**
 * Builder for custom types that should be represented as strings in the schema.
 * Allows specifying a conversion function from V to String.
 */
@JsonSchemaBuilderDsl
class CustomStringSchemaBuilder<V : Any> @PublishedApi internal constructor() : StringSchemaBuilder() {
    /**
     * Optional conversion function that transforms the custom type V into a String.
     * This is for documentation and potential runtime conversion.
     */
    var represent: (V.() -> String)? = null
}
