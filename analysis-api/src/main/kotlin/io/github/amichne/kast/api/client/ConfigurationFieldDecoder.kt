package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.flatMap
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

class ConfigurationFieldDecoder : NullHandlingDecoder<ConfigurationField<*>> {
    override fun supports(type: KType): Boolean =
        type.jvmErasure.isSubclassOf(ConfigurationField::class) && type.jvmErasure != ConfigurationField::class

    override fun safeDecode(
        node: Node,
        type: KType,
        context: DecoderContext,
    ): ConfigResult<ConfigurationField<*>> {
        val constructor = type.jvmErasure.primaryConstructor
                          ?: return ConfigFailure.MissingPrimaryConstructor(type).invalid()
        val valueParameter = constructor.parameters.singleOrNull { it.name == "value" }
                             ?: return ConfigFailure.Generic("${type.jvmErasure.simpleName} must have exactly one value constructor parameter")
                                 .invalid()

        return decodeValue(node, valueParameter, context).flatMap { value ->
            construct(type, constructor, value)
        }
    }

    private fun decodeValue(
        node: Node,
        valueParameter: KParameter,
        context: DecoderContext,
    ): ConfigResult<Any?> = if (valueParameter.type.jvmErasure == OptionalConfigString::class) {
        context.decoder(stringType)
            .flatMap { decoder -> decoder.decode(node, stringType, context) }
            .map { OptionalConfigString(it as String) }
    } else {
        context.decoder(valueParameter)
            .flatMap { decoder -> decoder.decode(node, valueParameter.type, context) }
    }

    private fun construct(
        type: KType,
        constructor: KFunction<*>,
        value: Any?,
    ): ConfigResult<ConfigurationField<*>> = try {
        val decoded = constructor.call(value) as? ConfigurationField<*>
        decoded?.valid()
        ?: ConfigFailure.Generic("${type.jvmErasure.simpleName} did not construct a ConfigurationField").invalid()
    } catch (exception: IllegalArgumentException) {
        ConfigFailure.InvalidConstructorParameters(
            type,
            constructor,
            mapOf(constructor.parameters.single() to value),
            exception
        ).invalid()
    }

    private companion object {
        val stringType: KType = String::class.createType()
    }
}
