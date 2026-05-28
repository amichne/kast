package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.PrimitiveNode
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KClass
import kotlin.reflect.KType

class ConfigurationFieldDecoder : NullHandlingDecoder<ConfigurationField<*>> {
    override fun supports(type: KType): Boolean =
        type.classifier in fieldDecodersByType

    override fun safeDecode(
        node: Node,
        type: KType,
        context: DecoderContext,
    ): ConfigResult<ConfigurationField<*>> {
        val fieldDecoder = fieldDecodersByType[type.classifier]
            ?: return ConfigFailure.DecodeError(node, type).invalid()

        return decodeValue(node, type, context, fieldDecoder.valueKind)
            .map(fieldDecoder.construct)
    }

    private fun decodeValue(
        node: Node,
        targetType: KType,
        context: DecoderContext,
        valueKind: ValueKind,
    ): ConfigResult<Any> = when (valueKind) {
        ValueKind.Boolean -> decodeBoolean(node, targetType)
        ValueKind.Int -> decodeInt(node, targetType)
        ValueKind.Long -> decodeLong(node, targetType)
        ValueKind.String -> decodeString(node, targetType, context)
        ValueKind.OptionalString -> decodeOptionalConfigString(node, targetType)
    }

    private fun decodeOptionalConfigString(
        node: Node,
        targetType: KType,
    ): ConfigResult<Any> = when (val decoded = decodeString(node, targetType, context = null)) {
        is com.sksamuel.hoplite.fp.Validated.Valid -> OptionalConfigString(decoded.value as String).valid()
        is com.sksamuel.hoplite.fp.Validated.Invalid -> decoded
    }

    private fun decodeString(
        node: Node,
        targetType: KType,
        context: DecoderContext?,
    ): ConfigResult<Any> = when (node) {
        is StringNode -> node.value.valid()
        is BooleanNode -> node.value.toString().valid()
        is LongNode -> node.value.toString().valid()
        is DoubleNode -> node.value.toString().valid()
        is ArrayNode -> if (context?.config?.flattenArraysToString == true && node.elements.all { it is PrimitiveNode }) {
            node.elements.joinToString(",") { (it as PrimitiveNode).value.toString() }.valid()
        } else {
            ConfigFailure.DecodeError(node, targetType).invalid()
        }
        else -> ConfigFailure.DecodeError(node, targetType).invalid()
    }

    private fun decodeBoolean(node: Node, targetType: KType): ConfigResult<Any> = when (node) {
        is BooleanNode -> node.value.valid()
        is StringNode -> when (node.value.lowercase()) {
            "true", "t", "1", "yes" -> true.valid()
            "false", "f", "0", "no" -> false.valid()
            else -> ConfigFailure.DecodeError(node, targetType).invalid()
        }
        else -> ConfigFailure.DecodeError(node, targetType).invalid()
    }

    private fun decodeInt(node: Node, targetType: KType): ConfigResult<Any> = when (node) {
        is LongNode -> node.value.toInt().valid()
        is DoubleNode -> node.value.toInt().valid()
        is StringNode -> node.value.toIntOrNull()?.valid()
            ?: ConfigFailure.NumberConversionError(node, targetType).invalid()
        else -> ConfigFailure.DecodeError(node, targetType).invalid()
    }

    private fun decodeLong(node: Node, targetType: KType): ConfigResult<Any> = when (node) {
        is LongNode -> node.value.valid()
        is StringNode -> node.value.toLongOrNull()?.valid()
            ?: ConfigFailure.NumberConversionError(node, targetType).invalid()
        else -> ConfigFailure.DecodeError(node, targetType).invalid()
    }

    private enum class ValueKind {
        Boolean,
        Int,
        Long,
        String,
        OptionalString,
    }

    private data class FieldDecoder(
        val valueKind: ValueKind,
        val construct: (Any) -> ConfigurationField<*>,
    )

    private companion object {
        val fieldDecodersByType: Map<KClass<*>, FieldDecoder> = mapOf(
            CacheEnabled::class to FieldDecoder(ValueKind.Boolean) { CacheEnabled(it as Boolean) },
            CacheSourceIndexSaveDelayMillis::class to FieldDecoder(ValueKind.Long) { CacheSourceIndexSaveDelayMillis(it as Long) },
            CacheWriteDelayMillis::class to FieldDecoder(ValueKind.Long) { CacheWriteDelayMillis(it as Long) },
            CliBinaryPath::class to FieldDecoder(ValueKind.String) { CliBinaryPath(it as String) },
            GradleToolingApiTimeoutMillis::class to FieldDecoder(ValueKind.Long) { GradleToolingApiTimeoutMillis(it as Long) },
            IndexingIdentifierIndexWaitMillis::class to FieldDecoder(ValueKind.Long) { IndexingIdentifierIndexWaitMillis(it as Long) },
            IndexingPhase2BatchSize::class to FieldDecoder(ValueKind.Int) { IndexingPhase2BatchSize(it as Int) },
            IndexingPhase2Enabled::class to FieldDecoder(ValueKind.Boolean) { IndexingPhase2Enabled(it as Boolean) },
            IndexingPhase2Parallelism::class to FieldDecoder(ValueKind.Int) { IndexingPhase2Parallelism(it as Int) },
            IndexingPhase2PriorityDepth::class to FieldDecoder(ValueKind.Int) { IndexingPhase2PriorityDepth(it as Int) },
            IndexingReferenceBatchSize::class to FieldDecoder(ValueKind.Int) { IndexingReferenceBatchSize(it as Int) },
            IndexingRemoteEnabled::class to FieldDecoder(ValueKind.Boolean) { IndexingRemoteEnabled(it as Boolean) },
            IndexingRemoteSourceIndexUrl::class to FieldDecoder(ValueKind.OptionalString) { IndexingRemoteSourceIndexUrl(it as OptionalConfigString) },
            IntellijBackendEnabled::class to FieldDecoder(ValueKind.Boolean) { IntellijBackendEnabled(it as Boolean) },
            PathsBinDir::class to FieldDecoder(ValueKind.String) { PathsBinDir(it as String) },
            PathsCacheDir::class to FieldDecoder(ValueKind.String) { PathsCacheDir(it as String) },
            PathsDescriptorDir::class to FieldDecoder(ValueKind.String) { PathsDescriptorDir(it as String) },
            PathsInstallRoot::class to FieldDecoder(ValueKind.String) { PathsInstallRoot(it as String) },
            PathsLibDir::class to FieldDecoder(ValueKind.String) { PathsLibDir(it as String) },
            PathsLogsDir::class to FieldDecoder(ValueKind.String) { PathsLogsDir(it as String) },
            PathsSocketDir::class to FieldDecoder(ValueKind.String) { PathsSocketDir(it as String) },
            ProfilingDurationSeconds::class to FieldDecoder(ValueKind.Long) { ProfilingDurationSeconds(it as Long) },
            ProfilingEmitManifest::class to FieldDecoder(ValueKind.Boolean) { ProfilingEmitManifest(it as Boolean) },
            ProfilingEnabled::class to FieldDecoder(ValueKind.Boolean) { ProfilingEnabled(it as Boolean) },
            ProfilingModes::class to FieldDecoder(ValueKind.String) { ProfilingModes(it as String) },
            ProfilingOtlpEndpoint::class to FieldDecoder(ValueKind.OptionalString) { ProfilingOtlpEndpoint(it as OptionalConfigString) },
            ProfilingOutputDir::class to FieldDecoder(ValueKind.String) { ProfilingOutputDir(it as String) },
            ServerMaxConcurrentRequests::class to FieldDecoder(ValueKind.Int) { ServerMaxConcurrentRequests(it as Int) },
            ServerMaxResults::class to FieldDecoder(ValueKind.Int) { ServerMaxResults(it as Int) },
            ServerRequestTimeoutMillis::class to FieldDecoder(ValueKind.Long) { ServerRequestTimeoutMillis(it as Long) },
            StandaloneBackendEnabled::class to FieldDecoder(ValueKind.Boolean) { StandaloneBackendEnabled(it as Boolean) },
            StandaloneRuntimeLibsDir::class to FieldDecoder(ValueKind.OptionalString) { StandaloneRuntimeLibsDir(it as OptionalConfigString) },
            TelemetryDetail::class to FieldDecoder(ValueKind.String) { TelemetryDetail(it as String) },
            TelemetryEnabled::class to FieldDecoder(ValueKind.Boolean) { TelemetryEnabled(it as Boolean) },
            TelemetryOutputFile::class to FieldDecoder(ValueKind.OptionalString) { TelemetryOutputFile(it as OptionalConfigString) },
            TelemetryScopes::class to FieldDecoder(ValueKind.String) { TelemetryScopes(it as String) },
            WatcherDebounceMillis::class to FieldDecoder(ValueKind.Long) { WatcherDebounceMillis(it as Long) },
        )
    }
}
